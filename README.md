# Svod

**Auditable, git-backed memory for AI agents — memory you can read, `git diff`, and restore.**

Svod is a local, headless knowledge base that gives one or many AI agents a shared, durable
memory and **never loses a file**. Every change an agent makes is an atomic, attributed git
commit over plain Markdown + YAML. An agent's memory stops being an opaque vector blob you
have to trust and becomes something you can open in any editor, search, diff, blame, branch,
and roll back — by hand or by tool.

It speaks **MCP** to agents (the same protocol Claude, Cursor, and the rest already use),
exposes a **local HTTP/WebSocket API** to UIs, and indexes everything for **hybrid search**
(keyword BM25 + semantic vectors, fused with RRF). One serialized writer guarantees no two
agents ever corrupt each other's work; optimistic concurrency means a stale write is rejected
with a conflict, never silently overwritten.

> **Positioning:** Svod is an alternative to opaque agent-memory stores (Mem0 / Letta / Zep),
> not a note-taking app and not a vector database you bolt on. The store of record *is* a git
> repo of Markdown — zero lock-in, full provenance.

---

## Why Svod exists

Agent memory today is usually one of two bad options:

1. **An opaque vector store.** Fast to write, impossible to audit. You can't see *what* the
   agent "remembered," you can't correct it without surgery, you can't diff it, and when it's
   wrong you can't tell *when* or *why* it went wrong. Recovery is "hope you have a backup."
2. **A pile of files an agent edits directly.** No concurrency control (two agents racing =
   lost writes), no history beyond the last save, no atomicity (a crash mid-write truncates a
   note), no search, no provenance.

Svod takes the auditable, durable substrate that already won for *code* — **git** — and makes
it a first-class memory backend for *agents*, behind a single safe writer and a real search
index. The result:

- **Nothing is ever lost.** Every mutation is a commit. A "deleted" file is a soft-delete to
  `.trash/` plus a commit — recoverable forever. Crash mid-write? Startup recovery completes
  or rolls back the partial write and cleans orphan temp files. `git fsck` stays clean.
- **Everything is attributable.** Each commit is authored by the specific agent (or the UI, or
  an external editor) that made it. "Which agent wrote this, and when?" is `git log`.
- **Wrong memory is fixable.** Bad note? `git revert`, restore a prior revision, or edit it in
  Obsidian. The agent sees the corrected state next read.
- **You are never locked in.** Export is the git tree. Import is a copy through the writer.
  The data is Markdown you already know how to read.

---

## What it does (feature tour)

| Capability | What it means in practice |
|---|---|
| **Single-writer integrity** | All writes funnel through one serialized actor (a coroutine + channel). No path bypasses it, so concurrent agents can never interleave a corrupt write. |
| **Atomic writes only** | tmp → fsync → rename → fsync dir. Never truncate-in-place, never hard `rm`. A power cut can't leave a half-written note. |
| **Git as durable history** | Every create/update/delete/move is a commit (via jgit). Full `log`, `diff`, `blame`, `restore`. The vault is a normal git repo you can clone. |
| **Optimistic concurrency** | Revision = git blob hash. Write with a stale revision → structured `conflict` + current content for a 3-way merge. **Never a silent overwrite.** |
| **Crash recovery** | On startup the engine reconciles the working tree against git, completes/rolls back partial writes, and removes orphan `.tmp` files. |
| **Hybrid search** | Lucene BM25 (lexical) + optional HNSW kNN vectors (semantic), fused with Reciprocal Rank Fusion. Heading-aware chunking. Filters by tag / path / date; fuzzy / prefix / phrase / field-scoped queries. |
| **Pluggable embeddings, in-process** | Default `onnx-local` runs `multilingual-e5-small` (MIT) via ONNX Runtime — no server, no Python, no network at query time. Or `ollama` (optional), or `none` for a guaranteed BM25-only baseline. |
| **MCP server for agents** | 12 tools over streamable HTTP, per-agent bearer tokens → git author, read-only/write roles, token-bucket rate limiting, append-only audit log, optional TLS. |
| **Local App API + WebSocket** | A loopback-only HTTP/JSON API for UIs, plus a live `/api/v1/events` stream (agent activity, commits, index updates, conflicts) so a UI feels alive. |
| **Wikilink graph** | `[[wikilinks]]` parsed into a backlink/outlink graph. Moving or renaming a note **rewrites every backlink in a single commit** — link integrity for free. |
| **Multiple vaults** | Run N vaults in one engine (e.g. `personal` + `work`), each its own git repo, lock, index, and sync remote. Routes select a vault via `?vault=`; agents are scoped to their granted vault. Cross-vault `[[vault:note]]` links resolve and surface as backlinks. |
| **Multi-host sync** | Replicate the vault across machines over git transport. Frontmatter-aware structured merge (YAML keys union, body via git 3-way). A designated merge authority keeps history deterministic; real conflicts are *surfaced*, never auto-resolved. |
| **Hardening** | Pre-commit secret scanning (a leaked key never enters history), live metrics (write latency, queue depth, index lag, conflicts, sync status), secret refs (`env:` / `file:` / macOS `keychain:`) instead of plaintext tokens. |
| **Obsidian import, zero lock-in** | One-shot migration of an existing Obsidian vault — markdown + **attachments** (images/PDF) preserved, frontmatter + wikilinks intact, `.obsidian/` skipped. Idempotent (re-run reconciles, never clobbers). Symlinks are skipped by default or, with `followSymlinks`, materialized (file + whole directory links copied in). Leaving is just `git clone`. |
| **External sources (re-syncable)** | Register a file/dir living *outside* the vault and re-sync its content in — the git-friendly answer to Obsidian's symlinked notes. *external-wins-unless-locally-edited*: external edits flow in, a vault copy you edited is a conflict (never clobbered). Optional `prune` soft-deletes files gone from the source (an edited copy is still protected); optional auto-sync on startup + interval. See [ADR-0016](docs/adr/0016-external-sources-and-symlink-import.md). |
| **UTF-8 / Cyrillic everywhere** | Filenames and content in any script. `core.quotepath=false`, tested with Cyrillic paths + YAML. |

---

## How it works (90-second architecture)

```
        AI agents (remote)            UI / scripts (local)            you (anytime)
              │                              │                            │
        MCP over HTTPS                 HTTP + WebSocket               git / editor /
        token + role                  127.0.0.1 only                  Obsidian
              │                              │                            │
              ▼                              ▼                            ▼
        ┌──────────────────────────────────────────────────────┐
        │                     Svod engine                        │
        │   ┌────────────────────────────────────────────────┐  │
        │   │      SINGLE WRITER (serialized actor)           │  │   atomic write,
        │   │   optimistic revision · soft-delete · commit    │  │   one commit per
        │   └────────────────────────────────────────────────┘  │   mutation
        │        │                    │                  │       │
        │   jgit (history)     Lucene (BM25+kNN)   wikilink graph │
        │        │                    ▲                          │
        │   file watcher  ───►  index off the write path         │
        └──────────────────────────────────────────────────────┘
                     │
              vault = a normal git repo of Markdown + YAML
                     │
              git transport ──► other Svod hosts (sync)
```

Reads fan out (search, history, graph — concurrent and cheap). **Writes are serialized**
through one actor, so integrity is structural, not best-effort. Indexing runs *off* the write
path, so a slow embed never blocks a write, and the index self-heals against git `HEAD` after
any wipe. The App API is **loopback-only**; remote agents come in exclusively through MCP with
TLS + per-agent tokens (architecture invariant #7).

Full detail: [`docs/architecture.md`](docs/architecture.md) and the ADRs in
[`docs/adr/`](docs/adr/).

---

## The agent interface — 12 MCP tools

Per-agent bearer token authenticates and **becomes the git commit author**. Roles are
enforced *before* the engine is touched.

**Read (any role):** `read` · `list` · `search` · `history` · `diff` · `get_revision` ·
`link` · `graph_query`
**Write (WRITE role):** `write` · `delete` · `move` · `promote`

- `write` / `delete` / `move` take an optional `expectedRevision` for optimistic concurrency —
  a mismatch returns a structured `conflict` (with current content), not a protocol error.
- `move` rewrites all backlinks atomically.
- `promote` moves a note from a scratch `messy/` namespace into the curated `vault/` (a
  revision-checked move) — the "draft → publish" path for agents.
- A read-only agent's mutation is denied and audited; an over-quota agent gets `rate_limited`;
  every action (including denials and conflicts) lands in an append-only audit log.

## The UI interface — local App API

Loopback HTTP/JSON, validated against [`contract/openapi.yaml`](contract/openapi.yaml) (the
versioned single source of truth for every client):

`/health` · `/ready` · `/api/v1/` `tree` · `file` (GET/PUT/DELETE) · `file/move` ·
`file/restore` · `file/history` · `file/diff` · `file/revision` · `file/links` · `search` ·
`graph` · `tags` · `settings` · `index/status` · `metrics` · `conflicts` ·
`conflicts/resolve` · `events` (WebSocket).

The `conflicts` endpoint carries each conflict's `base`/`ours`/`theirs` so a client can render a
3-way merge; `conflicts/resolve` commits the merged content through the single writer and clears
it — the engine half of the UI's conflict-resolution screen.

The WebSocket streams `agent.activity` / `commit.created` / `index.updated` / `file.changed` /
`conflict` / `engine.status` live — what powers the "watch your agents think" feed.

---

## Download & install

Prebuilt, self-contained app-images for every release are on the
**[Releases page](https://github.com/FleetQ/svod-engine/releases/latest)** — a bundled trimmed JVM,
no Java needed, every embedder including in-process `onnx-local` semantic search:

| OS | Download | Run |
|---|---|---|
| **macOS** (Apple Silicon) | `SvodEngine-macos-arm64.tar.gz` | `./SvodEngine.app/Contents/MacOS/SvodEngine config.json` |
| **Linux** (x64) | `SvodEngine-linux-x64.tar.gz` | `./SvodEngine/bin/SvodEngine config.json` |
| **Windows** (x64) | `SvodEngine-windows-x64.zip` | `SvodEngine\SvodEngine.exe config.json` |

```sh
# macOS / Linux
tar xzf SvodEngine-macos-arm64.tar.gz
./SvodEngine.app/Contents/MacOS/SvodEngine /path/to/config.json   # macOS (see Quickstart for the config)
./SvodEngine/bin/SvodEngine /path/to/config.json                  # Linux
```

> macOS Gatekeeper: release binaries are signed + notarized when a signing identity is configured;
> an unsigned build needs `xattr -dr com.apple.quarantine SvodEngine.app` (or right-click → Open) once.

**Run as a background service** so the engine is always up (and the UI's "Start Svod" can wake it):
- **macOS** — launchd; see [`dist/`](dist/) (`dev.svod.engine.plist` + `launchctl bootstrap`).
- **Linux** — a `systemd --user` unit running the binary with your config; enable + start it.
- **Windows** — Task Scheduler "At log on", or a service wrapper (e.g. NSSM) around the `.exe`.

### Native binary (single executable, no JVM)

Each release also ships a GraalVM `native-image` build — one self-contained executable with instant
startup and no bundled runtime. It serves the `none` (BM25) and `ollama` embedders; it does **not**
include in-process `onnx-local` semantic search (DJL/ONNX are JVM-only — use the app-image above for that).

| OS | Download | Run |
|---|---|---|
| **macOS** (Apple Silicon) | `svod-engine-macos-arm64` | `chmod +x svod-engine-macos-arm64 && ./svod-engine-macos-arm64 config.json` |
| **Linux** (x64) | `svod-engine-linux-x64` | `chmod +x svod-engine-linux-x64 && ./svod-engine-linux-x64 config.json` |
| **Windows** (x64) | `svod-engine-windows-x64.exe` | `svod-engine-windows-x64.exe config.json` |

Built on GraalVM CE for JDK 23 (the app-images stay on JDK 21); see
[ADR-0015](docs/adr/0015-native-image-and-cross-platform-releases.md) for the closed-world build details.

Build from source instead? Read on.

## Quickstart

Requires a JDK (20 used here; the Gradle wrapper is committed).

```sh
# 1. write a config (or copy dist/config.sample.json)
cat > /tmp/svod.json <<'JSON'
{
  "vaultPath": "/tmp/svod-vault",
  "host": "127.0.0.1",
  "appApiPort": 7517,
  "mcpPort": 7518,
  "embedder": { "provider": "none" },
  "agents": [
    { "token": "friday-write-token", "agentId": "friday", "role": "WRITE", "name": "Friday" },
    { "token": "reader-token",        "agentId": "reader", "role": "READ_ONLY" }
  ],
  "secretScanning": true,
  "webViewerPath": "examples/web-viewer"
}
JSON

# 2. run the engine  ("none" embedder = BM25-only, instant start, no model download)
cd engine
JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew run --args=/tmp/svod.json

# 3. it's up
curl -s http://127.0.0.1:7517/ready                 # {"ready":true,...}
open  http://127.0.0.1:7517/                         # the reference web viewer
```

Write a note through the loopback API (agents do the same over MCP):

```sh
curl -X PUT 'http://127.0.0.1:7517/api/v1/file?path=notes/first.md' \
  -H 'Content-Type: application/json' \
  -d '{"content":"# First note\n\nHello from Svod. [[second]]\n"}'
# → {"path":"notes/first.md","revision":"<blob>","commit":"<sha>"}

git -C /tmp/svod-vault log --oneline      # your agents' memory, as history
```

Switch `"embedder"` to `{ "provider": "onnx-local" }` for semantic search (downloads
`multilingual-e5-small`, ~120 MB, once, checksum-pinned). Package a self-contained
`SvodEngine.app` with [`dist/package.sh`](dist/package.sh). Run it under launchd with the
plist in [`dist/`](dist/). Build & test:
`cd engine && JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test`.

---

## Example scenarios

**1. A multi-agent crew with shared, non-colliding memory.**
A research agent, a writer agent, and a reviewer all hold the same Svod vault over MCP. They
write concurrently; the single writer serializes them so nothing is lost, and each note's
`git log` shows exactly which agent contributed what. The reviewer searches (`search`), reads
the writer's draft (`read`), and the writer `promote`s it from `messy/` to `vault/` only once
it's reviewed.

**2. "The agent remembered something wrong."**
You spot a bad fact in the agent's memory. Instead of black-box surgery, you open the note in
Obsidian (or `git revert` the offending commit). The file watcher ingests your edit as an
`external`-authored commit, re-indexes it, and the agent reads the corrected memory next time.
No special tooling, no migration.

**3. Long-running project knowledge that compounds.**
Over months, agents accumulate decisions, ADRs, and findings as linked notes. `[[wikilinks]]`
build a navigable graph; moving a note keeps every backlink intact. Hybrid search finds the
right note by meaning *or* keyword, in any language. Six months in, you can still answer "when
did we decide X, and which agent proposed it?" with `git log`.

**4. Two machines, one brain.**
Your laptop and a server both run Svod over the same git remote. Edits on either replicate;
YAML frontmatter merges key-by-key, note bodies merge via git's 3-way, and a genuine conflict
is surfaced at `/api/v1/conflicts` (both versions preserved) rather than silently picking a
winner. Each host holds the full history — lose a machine, lose nothing.

**5. A custom dashboard / second-brain UI.**
Point any client at the loopback API and subscribe to `/api/v1/events`. You get a live feed of
every agent action, jump-to-diff, search, graph, and one-click restore — without writing a
line of engine code, because the contract is versioned and stable. (`examples/web-viewer/` is
a working, dependency-free reference you can crib from.)

---

## How it compares

| | **Svod** | Mem0 | Letta / MemGPT | Zep | Plain Obsidian | Vector DB + RAG |
|---|---|---|---|---|---|---|
| **Store of record** | Git repo of Markdown | Vector + KV store | DB + vector store | DB + vector store | Markdown files | Embeddings |
| **Human-readable / editable** | ✅ any editor | ❌ opaque | ⚠️ via API | ❌ opaque | ✅ | ❌ |
| **Full version history / diff** | ✅ git | ❌ | ❌ | ⚠️ partial | ❌ (last save) | ❌ |
| **Per-change provenance (who/when)** | ✅ commit author | ⚠️ | ⚠️ | ⚠️ | ❌ | ❌ |
| **Never-lose-files guarantee** | ✅ atomic + soft-delete + recovery | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Concurrency control** | ✅ single writer + optimistic revision | ⚠️ | ⚠️ | ⚠️ | ❌ | ⚠️ |
| **Conflict surfaced, not auto-merged** | ✅ | ❌ | ❌ | ❌ | n/a | ❌ |
| **Hybrid search (BM25 + vectors)** | ✅ RRF | ⚠️ vector-first | ⚠️ | ✅ | ⚠️ plugins | ⚠️ vector-only |
| **Runs fully local, no Python/server** | ✅ single JVM, in-proc embeddings | ⚠️ | ⚠️ | ⚠️ | ✅ | ⚠️ |
| **Agent protocol** | ✅ MCP (12 tools) | SDK | SDK | SDK | ❌ | ❌ (DIY) |
| **Multi-host replication** | ✅ git transport | ⚠️ hosted | ⚠️ | ⚠️ hosted | ⚠️ sync apps | ⚠️ |
| **Lock-in** | None (git clone) | High | Medium | High | None | High |

**Where Svod wins:** auditability, durability, and provenance — memory as a *substrate you own
and can inspect*, not a service you query and trust. **Where the others may fit better:** if
you want a fully-hosted SaaS memory with zero ops and don't care that it's opaque, a managed
store is less setup. Svod's bet is that for serious, long-lived agent memory, "you can read and
restore it" beats "it's someone else's database."

*(Comparison reflects each tool's typical/default posture; specific configurations vary.)*

---

## Products & repos

| Repo | What |
|---|---|
| **`FleetQ/svod-engine`** (this repo, OSS) | The product: headless engine (Kotlin/JVM), the OpenAPI contract, packaging, docs, examples. OS-agnostic. |
| `FleetQ/svod-ui-macos` (separate, personal) | A personal SwiftUI client built against the published contract. **Not a supported product surface.** |

FleetQ is **one MCP client** — a first-party reference integration, not an embedded
dependency. The engine stays vendor-agnostic (see
[ADR-0002](docs/adr/0002-repo-split-and-license.md)).

## Layout

| Dir | What |
|---|---|
| `engine/` | Kotlin + Gradle engine. |
| `contract/` | OpenAPI spec — single source of truth for all clients. |
| `dist/` | launchd plist, jpackage/jlink packaging, install + secrets docs. |
| `examples/` | Reference integrations — start with `examples/web-viewer/`. |
| `docs/` | Architecture + ADRs. Start at [`docs/architecture.md`](docs/architecture.md) and [`docs/build-order.md`](docs/build-order.md). |

## Status

**v1.2.4 — stable, production.** Integrity core → hybrid index → MCP → App API/contract/graph/
watcher → lifecycle → reference viewer → multi-host sync → hardening → multi-vault + Obsidian
import → backup/DR + ops surface → external sources (prune + auto-sync) → non-blocking keyword-first
indexing + pluggable embedders (local-onnx / local-ollama / remote-openai) → restart robustness
(stale-lock self-heal, observable resume), Prometheus `/metrics`, opt-in reranking, oversized-chunk
fault tolerance → embedder model enumeration (`POST /embedder/models`). Full suite green;
CI gates every change; packaged as self-contained app-images **and** GraalVM `native-image` single
binaries for macOS / Linux / Windows (see **Download & install**). App API **contract 0.9.0**
(versioned independently of the engine). See [`docs/adr/`](docs/adr/) (ADR-0001 through 0017) for the decision record.

Deferred (documented, not hidden): encryption-at-rest (spec-optional; relies on disk encryption).
The GraalVM `native-image` binary now ships for all three OSes (built on GraalVM CE/JDK 23; serves
BM25/Ollama, not in-process onnx-local — see
[ADR-0015](docs/adr/0015-native-image-and-cross-platform-releases.md)).

## License

Licensed under the **Apache License 2.0** — see [LICENSE](LICENSE). Copyright 2026 FleetQ.
