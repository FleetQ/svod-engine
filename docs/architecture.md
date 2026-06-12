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
| `LuceneIndex` | BM25 always; HNSW kNN (cosine) **when vectors exist**; one doc per chunk. |
| `MarkdownChunker` | YAML frontmatter + heading-level chunks; SHA-256 content hash per chunk. |
| `Embedder` / `Embedders` | Pluggable seam + factory: `onnx-local` (default) / `ollama` / `none`. |
| `OnnxLocalEmbedder` | In-process e5-small via DJL + ONNX Runtime (`query:`/`passage:`, mean-pool, L2). |
| `ModelManager` | Resolves model.onnx + tokenizer.json: pre-placed or checksum-pinned download. |
| `OllamaEmbedder` / `NoneEmbedder` | Optional external provider / BM25-only baseline. |
| `GitReader` | Read-only jgit: HEAD tree, blobs, commit diffs — decoupled from the write actor. |
| `Rrf` | Reciprocal Rank Fusion (k=60) of the BM25 and kNN legs. |
| `IndexMeta` | Versioned `{schema, model, dim, headCommit}`; drives migration + self-heal. |

Semantic retrieval is opt-in over BM25: with `none` there are no vectors and search is
lexical-only. The engine notifies the index via an additive `onCommit` listener; the
handler only enqueues a sync, so writes never wait on embedding or Lucene I/O. See
[ADR-0003](adr/0003-hybrid-index.md) and [ADR-0004](adr/0004-embedder-providers-and-license.md).

## Engine internals: the MCP server (`dev.svod.engine.mcp`)

The agent-facing surface. Streamable HTTP (Ktor, loopback-only) via the official Kotlin MCP
SDK; per-agent bearer auth binds identity to each session.

| Type | Responsibility |
|---|---|
| `SvodMcpServer` | Ktor + streamable-HTTP wiring; per-session MCP `Server` bound to the agent. |
| `SvodTools` | Transport-agnostic 12-tool surface; enforces role + rate-limit + audit; maps to engine/index. |
| `AgentRegistry` / `AgentIdentity` | Bearer token → identity (git author) + role; constant-time compare. |
| `RateLimiter` | Per-agent token-bucket quota. |
| `AuditLog` | Append-only JSONL trail of every action under `.svod/audit/`. |
| `ToolResult` | `ok / conflict / not_found` (domain) vs `denied / rate_limited / bad_request` (`isError`). |
| `LinkGraph` (`graph`) | Lightweight `[[wikilink]]` resolution + backlinks for `link`/`graph_query`. |

Each tool call carries the authenticated agent, whose identity becomes the git commit author
— so multi-agent writes stay attributable and auditable. See [ADR-0005](adr/0005-mcp-server.md).

## Engine internals: the App API + graph + watcher (`api` / `events` / `graph` / `watch`)

The UI-facing surface, defined contract-first by [`contract/openapi.yaml`](../contract/openapi.yaml)
and bound to 127.0.0.1 only.

| Type | Responsibility |
|---|---|
| `AppApiServer` (`api`) | Ktor REST + WebSocket; every contract endpoint; single UI identity. |
| `EventBus` (`events`) | Non-blocking pub/sub; streamed over `/api/v1/events`. Published by engine/MCP/index/watcher. |
| `LinkGraph` / `LinkRewriter` (`graph`) | Full backlink/tag graph; transactional link rewriting on move. |
| `FileWatcher` (`watch`) | FSEvents → debounced, actor-serialized ingest of external edits. |
| `SvodEngine.moveWithLinks` | Move + rewrite all backlinks in one commit (link-integrity). |
| `SvodEngine.ingestExternalChanges` | Commit dirty working tree as `external` (used by the watcher). |

The contract is the single source of truth: a test validates every live response against
`openapi.yaml` and that routes == declared paths. See [ADR-0006](adr/0006-app-api-contract-watcher-graph.md).

## Engine internals: lifecycle (`dev.svod.engine.lifecycle` + `dist/`)

| Type | Responsibility |
|---|---|
| `SvodConfig` | Centralized JSON config; validated at startup (loopback enforced). |
| `SvodNode` | Assembles + owns the node; single-instance; readiness; ordered graceful shutdown. |
| `ApiCompatibility` / `SelfUpdate` | Semver gate: a self-update must keep the App API major compatible. |
| `dist/launchd/*.plist` | macOS user agent: RunAtLoad + KeepAlive; one-button start via `kickstart`. |
| `dist/self-update.sh` | Update skeleton gated on the compat preflight. |

The engine stays OS-agnostic; all macOS specifics live in `dist/`. launchd uses KeepAlive +
kickstart rather than fd socket activation (JVM limitation). See [ADR-0007](adr/0007-lifecycle.md).

## Toolchain notes (this machine)

- JDK 20 (no 21 present) — Gradle toolchain targets 20; fine for jpackage/jlink later.
- Gradle 8.12 (wrapper committed). **Kotlin 2.3.21** (bumped from 2.1 to read the MCP SDK
  0.13.0 metadata), coroutines 1.9.0, jgit 6.7.0.
- MCP: `io.modelcontextprotocol:kotlin-sdk` **0.13.0** + **Ktor 3.4.3** (CIO), loopback-only.
- App API: Ktor 3.4.3 (REST + WebSocket); `io.methvin:directory-watcher` (FSEvents);
  contract test via `com.atlassian.oai:swagger-request-validator`.
- Swift 6.3 / Xcode 26.5 available (the SwiftUI client lives in its own repo).
- **No external embedding server required.** Default `onnx-local` runs `multilingual-e5-small`
  (MIT, 384-dim, int8) in-process via DJL 0.30 + ONNX Runtime; model cached under
  `.svod/models/`. Ollama is an optional provider (installed here at `127.0.0.1:11434`).
- Lucene pinned to **9.12** (Lucene 10 needs JDK 21, absent here).
