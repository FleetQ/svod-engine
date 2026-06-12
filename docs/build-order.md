# Svod — build order & status

Each step is fully done + tested before the next. The gate rule: **do not move past the
integrity core until its concurrency + crash tests pass.**

| # | Subsystem | Status | Notes |
|---|---|---|---|
| 1 | **Integrity core** — write-actor, jgit atomic commit, optimistic revision, soft-delete, crash recovery, vault lock, single-instance | ✅ **done, gate green** | `dev.svod.engine.core`; 12 tests passing (concurrency, crash-injection, Cyrillic). See ADR-0001. |
| 2 | Lucene hybrid index + incremental indexing + embedding pipeline (Ollama e5) + self-heal vs git HEAD | ⬜ todo | Needs Ollama installed. |
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

## How to run the gate

```sh
cd engine
JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test
```
