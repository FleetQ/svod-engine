# Svod — build order & status

Each step is fully done + tested before the next. The gate rule: **do not move past the
integrity core until its concurrency + crash tests pass.**

| # | Subsystem | Status | Notes |
|---|---|---|---|
| 1 | **Integrity core** — write-actor, jgit atomic commit, optimistic revision, soft-delete, crash recovery, vault lock, single-instance | ✅ **done, gate green** | `dev.svod.engine.core`; 12 tests passing (concurrency, crash-injection, Cyrillic). See ADR-0001. |
| 2 | **Lucene hybrid index** — BM25 baseline + opt-in HNSW kNN + RRF, heading chunking, **pluggable embedder** (`onnx-local` default / `ollama` / `none`), incremental-from-commits, model-change migration, self-heal vs HEAD | ✅ **done, gate green** | `dev.svod.engine.index`; 27 tests (incl. in-process ONNX e5-small + live-Ollama). See ADR-0003, ADR-0004. |
| 3 | MCP server — read/write/delete/move/search/list/history/diff/get_revision/link/graph_query/promote; audit log; token auth; roles; rate limiting; `messy/`→`vault/` promotion | ⬜ todo | |
| 4 | App API + OpenAPI contract + file watcher + wikilink/backlink graph + link-integrity on rename/move | ⬜ todo | `contract/openapi.yaml` first. |
| 5 | Lifecycle — launchd socket activation, `/health` + `/ready`, single-instance, graceful shutdown, self-update w/ API-compat check | ⬜ todo | `dist/`. |
| 6 | Reference web viewer in `examples/` ("watch agents write, then `git diff` their memory") | ⬜ todo | Product demo; built once App API (step 4) exists. |
| 7 | Multi-host sync — replicated engines + git transport, frontmatter-aware merge, designated merge authority, conflicts surfaced | ⬜ todo | |
| 8 | Hardening — TLS, Keychain tokens, secret scanning, observability metrics, Obsidian import, full test suite | ⬜ todo | |

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

## How to run the gate

```sh
cd engine
JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test          # ONNX/Ollama tests auto-skip if model/server absent
JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test --rerun-tasks   # force a non-cached run
```

The default `onnx-local` embedder needs no server: `multilingual-e5-small` (MIT, 384-dim,
int8 ONNX) is downloaded once into `.svod/models/` (checksum-pinned) and cached. `none` is
the BM25-only baseline with no model at all. `ollama` is an optional provider.
