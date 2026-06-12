# Svod — build order & status

Each step is fully done + tested before the next. The gate rule: **do not move past the
integrity core until its concurrency + crash tests pass.**

| # | Subsystem | Status | Notes |
|---|---|---|---|
| 1 | **Integrity core** — write-actor, jgit atomic commit, optimistic revision, soft-delete, crash recovery, vault lock, single-instance | ✅ **done, gate green** | `dev.svod.engine.core`; 12 tests passing (concurrency, crash-injection, Cyrillic). See ADR-0001. |
| 2 | **Lucene hybrid index** — BM25 + HNSW kNN + RRF, heading chunking, Ollama e5 embeddings, incremental-from-commits, model-change migration, self-heal vs HEAD | ✅ **done, gate green** | `dev.svod.engine.index`; 17 tests (incl. live-Ollama semantic). See ADR-0003. |
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

- [x] Hybrid search (BM25 + HNSW kNN, RRF k=60) correct on a seeded corpus incl. Cyrillic.
- [x] Index reconstructs exactly from git HEAD after a full wipe (self-heal proven).
- [x] Incremental update re-embeds only changed chunks (asserted: 3 sections → edit 1 → 1 re-embed).
- [x] Reindex-on-model-change (and dim-change) produces a consistent, queryable index.
- [x] Indexing runs off the write path; gated-embedder test proves writes never block and
      step-1 integrity (tree==HEAD, `git fsck` clean) is preserved under concurrent indexing.
- [x] Filters (tag / path / date) + fuzzy / prefix / phrase / field-scoped queries.
- [x] Real semantic + cross-lingual retrieval via live Ollama (auto-skipped in CI).

## How to run the gate

```sh
cd engine
JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test          # 29 tests (Ollama test auto-skips if down)
JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test --rerun-tasks   # force a non-cached run
```

Ollama must be running for the semantic integration test to execute (it skips cleanly if not):
`brew services start ollama` and pull `zylonai/multilingual-e5-large` (1024-dim).
