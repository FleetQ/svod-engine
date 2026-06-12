# Svod — architecture overview

Svod is a local, git-backed markdown knowledge base that serves multiple local AI agents
and **never loses files**. It is a standalone OSS product — *auditable, git-backed agent
memory you can read, diff, and restore* — not a note app.

- **Svod engine** (this repo, `FleetQ/svod-engine`, OSS) — headless Kotlin/JVM daemon; the
  single writer and source of truth. Stays vendor-agnostic; FleetQ is just one MCP client.
- **Svod UI** (`FleetQ/svod-ui-macos`, separate & personal) — a SwiftUI client built
  against the published OpenAPI contract. **Not a supported product surface.** See
  [ADR-0002](adr/0002-repo-split-and-license.md).

```
        agents (Mac, friday, sage-production)         UI (SwiftUI, swappable)
                     │  MCP (streamable HTTP,                 │  App API
                     │  TLS + per-agent token)                │  (HTTP/JSON + WebSocket,
                     ▼                                        ▼   loopback only, OpenAPI)
        ┌─────────────────────────────────────────────────────────────────┐
        │                          Svod engine (JVM)                        │
        │   MCP server   │   App API + WS   │   lifecycle (launchd)         │
        ├─────────────────────────────────────────────────────────────────┤
        │   Lucene hybrid index (BM25 + HNSW kNN, RRF)  │  graph / links    │
        ├─────────────────────────────────────────────────────────────────┤
        │   ▓▓ INTEGRITY CORE ▓▓  single-writer actor · atomic write ·      │
        │   jgit history · optimistic revision · soft-delete · recovery     │
        └─────────────────────────────────────────────────────────────────┘
                              │ working tree + .git
                              ▼
                     markdown vault (UTF-8, Cyrillic-safe)
```

## Invariants (non-negotiable)

1. **Single writer.** All writes go through one serialized actor. No bypass.
2. **Atomic writes only** — tmp → fsync → rename → fsync(dir). Never truncate-in-place.
   Never hard `rm`; soft-delete to `.trash/` + commit.
3. **Git is durable history.** Every mutation is a commit authored by the agent/UI. A
   lost file is always git-recoverable.
4. **Optimistic concurrency.** revision = blob hash; mismatch ⇒ `conflict` + current
   content. Never silently overwrite.
5. **Crash recovery on startup** reconciles tree ↔ git, completes partial writes, cleans
   orphan `.tmp`.
6. **Contract-first.** `contract/openapi.yaml` is the single source of truth; engine and
   UI release independently against a versioned contract.
7. **App API binds 127.0.0.1 only.** Remote agent access only via MCP (TLS + token).
8. **UTF-8 / Cyrillic everywhere** — `core.quotepath=false`, tested.

## Module layout

- `engine/` — Kotlin + Gradle. `dev.svod.engine.core` is the integrity core (this step).
- `contract/` — OpenAPI spec. (step 4)
- `dist/` — launchd plist, jpackage/jlink config, installers. (step 5)
- `examples/` — reference integrations: web viewer + FleetQ-MCP (built from step 4).
- `docs/` — architecture + ADRs (one per major decision).

The SwiftUI client lives in a separate, personal repo (`FleetQ/svod-ui-macos`), not here.

## Engine internals: the integrity core (`dev.svod.engine.core`)

| Type | Responsibility |
|---|---|
| `SvodEngine` | Public API; orchestrates open/recovery and the mutation handlers. |
| `WriteActor` | The single writer — one coroutine on one dedicated thread. |
| `AtomicFile` | tmp→fsync→rename→fsync(dir), with injectable `CrashPoint` for tests. |
| `GitRepo` | jgit wrapper: blob-id revision, commit-per-mutation, history. |
| `VaultLock` | OS advisory exclusive lock ⇒ single-instance. |
| `VaultPath` | Validated vault-relative path; blocks traversal + reserved dirs. |
| `WriteOutcome` | `Success \| Conflict \| NotFound` — failures as values. |

See [ADR-0001](adr/0001-integrity-core.md) for the rationale behind each decision and
[build-order.md](build-order.md) for status.

## Engine internals: the hybrid index (`dev.svod.engine.index`)

Derived, rebuildable-from-HEAD search. Reads committed blobs only (its own read-only git
handle) and runs entirely **off** the write path.

| Type | Responsibility |
|---|---|
| `IndexService` | Owns the index; sync/reconcile/migrate on one `svod-indexer` thread; hybrid `search()`. |
| `LuceneIndex` | BM25 + HNSW kNN (cosine) in one doc per chunk; stores vectors for reuse. |
| `MarkdownChunker` | YAML frontmatter + heading-level chunks; SHA-256 content hash per chunk. |
| `OllamaEmbedder` | e5 embeddings via Ollama (`passage:`/`query:` prefixes); `Embedder` is the seam. |
| `GitReader` | Read-only jgit: HEAD tree, blobs, commit diffs — decoupled from the write actor. |
| `Rrf` | Reciprocal Rank Fusion (k=60) of the BM25 and kNN legs. |
| `IndexMeta` | Versioned `{schema, model, dim, headCommit}`; drives migration + self-heal. |

The engine notifies the index via an additive `onCommit` listener; the handler only
enqueues a sync, so writes never wait on embedding or Lucene I/O. See
[ADR-0003](adr/0003-hybrid-index.md).

## Toolchain notes (this machine)

- JDK 20 (no 21 present) — Gradle toolchain targets 20; fine for jpackage/jlink later.
- Gradle 8.12 (wrapper committed). Kotlin 2.1.0, coroutines 1.9.0, jgit 6.7.0.
- Swift 6.3 / Xcode 26.5 available (the SwiftUI client lives in its own repo).
- **Ollama installed** (`brew`, v0.30) running on `127.0.0.1:11434`; model
  `zylonai/multilingual-e5-large` (1024-dim) pulled and used by step 2.
- Lucene pinned to **9.12** (Lucene 10 needs JDK 21, absent here).
