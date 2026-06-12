# Svod — build order & status

**All 8 steps complete and green — 89 tests, 0 failed.** Svod is a runnable, replicating,
contract-driven engine: integrity core → hybrid index → MCP → App API/contract/graph →
lifecycle → reference viewer → multi-host sync → hardening.

Each step was fully done + tested before the next. The gate rule: **do not move past the
integrity core until its concurrency + crash tests pass.**

| # | Subsystem | Status | Notes |
|---|---|---|---|
| 1 | **Integrity core** — write-actor, jgit atomic commit, optimistic revision, soft-delete, crash recovery, vault lock, single-instance | ✅ **done, gate green** | `dev.svod.engine.core`; 12 tests passing (concurrency, crash-injection, Cyrillic). See ADR-0001. |
| 2 | **Lucene hybrid index** — BM25 baseline + opt-in HNSW kNN + RRF, heading chunking, **pluggable embedder** (`onnx-local` default / `ollama` / `none`), incremental-from-commits, model-change migration, self-heal vs HEAD | ✅ **done, gate green** | `dev.svod.engine.index`; 25 tests (incl. in-process ONNX e5-small + live-Ollama). See ADR-0003, ADR-0004. |
| 3 | **MCP server** — all 12 tools over streamable HTTP; per-agent token auth → git author; read-only/write roles; rate limiting; append-only audit; `messy/`→`vault/` promotion | ✅ **done, gate green** | `dev.svod.engine.mcp`; 10 tests (8 hermetic + 2 real-HTTP via MCP client). See ADR-0005. |
| 4 | **App API + OpenAPI contract** + file watcher + wikilink/backlink graph + link-integrity on rename/move | ✅ **done, gate green** | `contract/openapi.yaml` (contract-first) + `dev.svod.engine.{api,events,graph,watch}`; 8 tests (contract conformance, WS events, watcher, link-integrity). See ADR-0006. |
| 5 | **Lifecycle** — launchd (KeepAlive + kickstart), `/health` + `/ready`, single-instance, graceful shutdown, validated config, self-update API-compat check | ✅ **done, gate green** | `dev.svod.engine.lifecycle` + `dist/`; 10 tests (config, compat matrix, node start/shutdown/no-loss, single-instance, readiness). See ADR-0007. |
| 6 | **Reference web viewer** in `examples/web-viewer` ("watch agents write, then `git diff` their memory") | ✅ **done, verified in browser** | Dependency-free; served same-origin (opt-in) by the App API; live feed + git-diff. See ADR-0008. |
| 7 | **Multi-host sync** — replicated engines + git transport, frontmatter-aware merge, designated merge authority, conflicts surfaced | ✅ **done, gate green** | `dev.svod.engine.sync`; 10 tests (frontmatter merge incl. Cyrillic + two-engine replication: converge, structural merge, conflict surfacing). See ADR-0009. |
| 8 | **Hardening** — secret scanning, metrics, TLS for MCP, secret store, Obsidian import, full test suite | ✅ **done, gate green** | `dev.svod.engine.{security,obs,migrate}` + Netty TLS; 11 tests. See ADR-0010. |

> The personal **SwiftUI client** moved to its own repo (`FleetQ/svod-ui-macos`) and is
> **out of product scope** — not a build step here. See [ADR-0002](adr/0002-repo-split-and-license.md).

## Step-1 acceptance (met)

- [x] Single writer; no path bypasses the actor.
- [x] Atomic write (tmp→fsync→rename→fsync dir); never truncate-in-place; never hard rm.
- [x] Every mutation is a git commit authored by caller; history recoverable.
- [x] Optimistic concurrency: blob-hash revision; mismatch ⇒ conflict + current content.
- [x] Crash recovery: clean orphan tmp; complete partial writes (never lose files).
- [x] Vault lock + single-instance.
- [x] UTF-8 / Cyrillic paths + content (`core.quotepath=false`), tested.
- [x] Race + fuzz tests prove no lost updates under parallel agent writes.
- [x] Crash-injection at every write stage leaves a consistent tree + `git fsck` clean.

## Step-2 acceptance (met)

- [x] BM25-only (`none`) returns correct results incl. Cyrillic; hybrid (with e5) correct;
      RRF improves ranking over either leg alone on a designed case.
- [x] e5 prefix + attention-mask mean-pool + L2-normalize verified; a known query/passage
      pair is pinned (unit-length, `cos≈0.88`, cross-lingual alignment).
- [x] Embedder swap via config works: `onnx-local ↔ none ↔ ollama`; `none` leaves a fully
      usable lexical search.
- [x] Index reconstructs exactly from git HEAD after a full wipe (self-heal proven).
- [x] Incremental update re-embeds only changed chunks (asserted: 3 sections → edit 1 → 1 re-embed).
- [x] Reindex-on-model/dims-change produces a consistent, queryable index.
- [x] Indexing runs off the write path; gated-embedder test proves writes never block and
      step-1 integrity (tree==HEAD, `git fsck` clean) is preserved under concurrent indexing.
- [x] Filters (tag / path / date) + fuzzy / prefix / phrase / field-scoped queries.

## Step-3 acceptance (met)

- [x] All 12 tools callable over real streamable-HTTP via the MCP client.
- [x] Per-agent bearer token → identity; a write's git commit is authored by that agent (proven over HTTP).
- [x] Roles enforced: a read-only agent's mutation is denied before the engine is touched.
- [x] Per-agent token-bucket rate limiting returns `rate_limited` past quota.
- [x] Append-only audit log records every action (incl. denials/conflicts) with agent identity.
- [x] `messy/`→`vault/` promotion (revision-checked move); bad namespace rejected.
- [x] Conflict/not-found returned as structured results (current content for 3-way merge), not protocol errors.
- [x] Unknown bearer token rejected at the transport.

## Step-4 acceptance (met)

- [x] `contract/openapi.yaml` written first; every App API response validated against it and
      implemented routes == declared paths (contract test fails on drift).
- [x] All endpoints work: tree, file CRUD, search, history, diff, revision, restore, graph,
      links, tags, settings, index status, conflicts, health/ready.
- [x] WebSocket `/api/v1/events` streams live `agent.activity` / `commit.created` /
      `index.updated` (+ `file.changed` / `conflict` / `engine.status`).
- [x] File watcher ingests external working-tree edits via the write-actor (committed as
      `external`, indexed); engine's own writes are not re-ingested (no loop).
- [x] Link-integrity: moving a note rewrites all backlinks in ONE commit (path + basename
      styles, alias/heading preserved); ambiguous basenames left untouched.
- [x] App API binds 127.0.0.1 only; no per-agent auth (loopback-trusted UI identity).

## Step-5 acceptance (met)

- [x] Centralized config validated at startup (port/loopback/provider/tokens) — fails fast.
- [x] Single-instance via the vault lock (second node on a vault refused).
- [x] `/health` (liveness) + `/ready` (readiness flag; 503 before ready, 200 after).
- [x] Graceful ordered shutdown drains the write-actor queue and releases the lock —
      write → shutdown → fresh node reads it back; port closed.
- [x] launchd plist (RunAtLoad + KeepAlive) + one-button start via `launchctl kickstart`.
- [x] Self-update gated on App API semver compatibility (major-match; downgrade refused).

## Step-7 acceptance (met)

- [x] Replicated engines over git; each host holds the full history (never lose files).
- [x] Designated merge authority: only it creates merge commits; replicas propose + fast-forward
      (deterministic history across the fleet).
- [x] Frontmatter-aware structured merge: YAML keys merge at the key level (tag union, one-side
      wins), body via git's 3-way; Cyrillic + YAML proven.
- [x] Conflicts surfaced via `/api/v1/conflicts`, never auto-resolved; both versions preserved
      (theirs in the conflict record + git history). No silent overwrite.
- [x] Sync's ref-moving writes go through the single writer; `git fsck` clean after merges.

## Step-8 acceptance (met)

- [x] Secret scanning blocks leaked credentials before they enter git (`WriteOutcome.Blocked`).
- [x] Metrics at `/api/v1/metrics`: write latency, queue depth, index lag, conflicts, sync status.
- [x] MCP served over TLS (Netty + sslConnector); proven by a real client TLS handshake.
- [x] Secrets resolved from `env:` / `file:` refs (tokens, keystore passwords) — not plaintext.
- [x] Obsidian import preserves frontmatter + wikilinks, skips `.obsidian/`; zero lock-in.
- [x] Full suite green (89 tests).

## How to run the gate

```sh
cd engine
JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test          # ONNX/Ollama tests auto-skip if model/server absent
JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test --rerun-tasks   # force a non-cached run
```

The default `onnx-local` embedder needs no server: `multilingual-e5-small` (MIT, 384-dim,
int8 ONNX) is downloaded once into `.svod/models/` (checksum-pinned) and cached. `none` is
the BM25-only baseline with no model at all. `ollama` is an optional provider.
