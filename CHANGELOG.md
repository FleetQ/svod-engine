# Changelog

All notable changes to the Svod engine. The App API contract (`contract/openapi.yaml`) is versioned
independently of the engine; each entry notes the contract version it ships.

## v1.6.4 — 2026-06-17 (App API contract 0.14.0)

### Fixed — Windows release no longer drops all its assets when MSVC setup breaks
- The release's Windows job died at `Set up MSVC` (`ilammy/msvc-dev-cmd`) after the GitHub runner
  shipped a new Visual Studio (VS 18 moved `vcvarsall.bat` to a path the action doesn't probe),
  which failed the whole job and dropped *both* Windows assets — including the app-image `.zip`
  that had already built. Marked the MSVC step `continue-on-error` (the native binary was always
  best-effort): the reliable Windows app-image now ships even when the native build can't link.
  Engine code unchanged from v1.6.3; this is a CI/release-workflow fix only.

## v1.6.3 — 2026-06-17 (App API contract 0.14.0)

### Fixed — O(vault) git operations made every write multi-second on large vaults
On a large vault (~10k+ tracked files) external-source auto-sync appeared not to run at all. The
watcher, debounce and worker were fine (confirmed by DEBUG logs) — the cost was **two O(working-tree)
git operations** on the single write-actor, each walking/statting *every* tracked file (~3–4 ms/file,
i.e. tens of seconds), so every change blocked the actor and the synced file only appeared much later.
Two fixes, both making the cost **O(changed paths)** instead of O(working tree):

- **Path-scoped commits.** Writes committed via `git add .` + a full `git status()`. New
  `GitRepo.commitPaths(paths, …)` edits the index (DirCache) directly — reads only the changed files,
  splices their blobs, writes the tree, moves HEAD — no working-tree walk. All path-aware write paths
  (single write, batch/source-sync, delete, move, move-with-backlinks, restore) use it; `.gitignore`
  handling and no-op detection are unchanged. Full-tree `commitAll` is kept only where semantically
  required (post-sync merge ingest, crash recovery, vault init).
- **Path-scoped external-change ingest.** The vault `FileWatcher` ran a full-tree `commitAll` on
  *every* change — including the engine's own writes — finding nothing new to commit (so no duplicate
  commit) but still burning the full-tree walk on the actor, blocking the next read/write. It now
  ingests only the paths its FS events actually touched (`ingestExternalChanges(paths)`), so an
  engine-written path is a cheap no-op and a genuine external edit still commits.
- **Measured on the live vault: steady-state auto-sync dropped from ~8–37 s to ~0.55 s (median);** a
  burst and a series of spaced edits converge in <1 s.
- Added DEBUG logging for external-source auto-sync (`fs event` / `auto-sync starting|done`) under the
  `dev.svod.engine.sources` logger, so event delivery and sync runs are visible in the log.

## v1.6.2 — 2026-06-17 (App API contract 0.14.0)

### Fixed — external-source auto-sync reliability under load
- **Auto-sync no longer stalls or cancels itself.** The per-source sync worker is now **decoupled from
  the watch lifecycle**. Previously the sync coroutine ran on the watcher's own scope, so when the
  native FSEvents watch died under load (its future completes) the 30s supervisor tore the whole
  watcher down and **cancelled the in-flight sync** (`WARN ... auto-sync ... failed: Job was
  cancelled`), and while dead it missed events — a series of edits could never converge.
- **Self-healing watch + trailing debounce (single-pending).** The DirectoryWatcher now re-arms
  itself (with backoff, plus a catch-up sync) without touching the worker; a running sync is **never
  cancelled** by a restart. The worker coalesces a burst into one sync `debounceMs` after the *last*
  event, and an edit arriving during a sync schedules exactly one follow-up — so every burst yields a
  completed sync, never a cancel-chain.
- **Expected cancellation is no longer logged as an error** — `CancellationException` is propagated
  (DEBUG), and the manager no longer tears a watcher down for a transient `!isAlive`. Real IO/scan
  failures still WARN. No API/contract change.

## v1.6.1 — 2026-06-17 (App API contract 0.14.0)

### Changed — external-source auto-sync latency
- **Sub-second auto-sync.** Lowered the per-source watcher debounce from 700 ms to 250 ms, cutting the
  measured edit→vault-update latency from ~757 ms to ~285 ms median (5 runs) — comfortably under 1 s
  even against FSEvents' 0.5 s worst-case coalescing window. The watcher already used the native
  FSEvents-backed `io.methvin:directory-watcher` (not the JDK `WatchService` polling fallback), so
  detection was never the bottleneck — the debounce was. Atomic-save (temp+rename) coalescing and the
  burst→single-sync behavior are unchanged (the existing coalescing test already ran at 250 ms).
  No API/contract change.

## v1.6.0 — 2026-06-17 (App API contract 0.14.0)

### Added — memory-system primitives (borrowed from "RAG → Memory Systems")
- **Memory typing** — a reserved frontmatter `type` (`policy|preference|fact|episode|note`, free-form)
  is indexed and filterable (`GET /search?type=`, MCP `search`/`context_pack` `type`), so the vault is
  no longer one undifferentiated corpus.
- **Lifecycle hiding** — frontmatter `status` (`active|provisional|revoked`), `superseded_by`, and
  `expires_at` are honored as retrieval filters: recall excludes revoked / provisional / superseded /
  past-expiry memories by default (`includeAll=true` to see them, or filter `status=` explicitly).
  Notes without these keys are **completely unaffected** (backward compatible).
- **Path A enumeration** — `context_pack` with `enumerate=true` returns *every* note matching a
  `type`/`tags` filter **in full, unranked, deterministic** (capped at 500), ignoring the token budget:
  the "rule book" (all active policies/preferences) loaded verbatim every turn, vs. ranked semantic
  recall (Path B).
- **`remember` MCP tool (promotion gate)** — turns an observation into a durable typed memory note:
  classify + scope (to the vault), dedup by normalized content hash (`memory/<type>/<hash>.md`), set
  `status` by type (fact/policy → `provisional`, preference/episode → `active`), and optionally
  `supersedes` a prior memory (revoke + link). Keeps an agent-written KB from poisoning its own recall.
  MCP tool count 13 → **14**.

### Changed
- App API **contract 0.14.0** (additive): `/search` gains `type`, `status`, `includeAll` query params
  and documents the reserved frontmatter keys + default lifecycle visibility.

### Not adopted / deferred
Episodic distillation (consolidation/summarization) deferred. Did **not** adopt the article's SQL
backend, multi-tenant row scoping (vault = scope), or its linear 0.4/0.6 fusion (Svod's RRF+reranker
stands). All new behavior is frontmatter conventions + Lucene filters — no datastore change.

## v1.5.0 — 2026-06-16 (App API contract 0.13.0)

### Added — per-source filesystem auto-sync
- **Auto-sync external sources on change.** A registered source can now opt into `autoSync`: a native
  FSEvents-backed filesystem watcher re-syncs it into the vault ~1s after its files settle, so an
  external project's docs flow in automatically without a manual sync. Events are debounced/coalesced
  (an editor's atomic temp-file-then-rename becomes a single sync) and noisy temp/dot files are
  ignored. Sync semantics are unchanged (it reuses `SourceSync`): external-wins-unless-locally-edited,
  conflict-preserve, prune soft-delete, secret-scanner skip, incremental reindex.
- **Runtime control, no restart**: `autoSync` on `POST /api/v1/sources` (idempotent by path) and a new
  **`PATCH /api/v1/sources/{id}`** `{ autoSync?, followSymlinks?, prune? }` that starts/stops the
  watcher immediately. Each source response carries `autoSync` and a live read-only `watching` flag.
- **`source.synced` event** (`{ vault, sourceId, created, updated, conflicts, deleted }`) emitted on
  each auto-sync, for the UI to reflect.
- The existing global scheduled `sourceSync` (config polling) stays as a coarse safety-net for hosts
  where native watching doesn't fire. A vanished source path stops its watcher (logged, no crash) and
  is re-watched when it reappears.

### Changed
- App API **contract 0.13.0** (additive): `autoSync`/`watching` on `ExternalSource`, `autoSync` on
  `RegisterSourceRequest`, new `PATCH /sources/{id}` + `PatchSourceRequest`, `source.synced` event.

### Out of scope (documented)
Watching remote/network paths; two-way (vault → source) flow — sources stay input-only.

## v1.4.0 — 2026-06-16 (App API contract 0.12.0)

### Added — two-way multi-machine sync
- **Multi-machine sync** over git as a bidirectional bus. A single user's machines edit the same
  vault and converge with no manual git. Symmetric topology (no authority/replica): one shared
  canonical ref `refs/svod/sync/<vaultId>` on the same remote used for backup. The sync cycle —
  commit pending → fetch → fast-forward / 3-way merge → non-force push (bounded retry on a
  non-fast-forward) — runs on a background poll (`syncIntervalMinutes`, default 3), on
  `POST /api/v1/sync/now`, and debounced after local writes settle.
- **Conflict handling**: a clean merge (structural YAML frontmatter + git line-level body, via
  `FrontmatterMerge`) commits automatically; a real overlap (or modify/delete) aborts the merge,
  leaves the local tree untouched, and records base/ours/theirs in the conflict queue. The user
  resolves via `POST /api/v1/conflicts/resolve`, which finalizes the merge commit. Nothing is lost.
- **Defense-in-depth**: an incoming file that trips the secret scanner is quarantined as a conflict,
  never written into the vault.
- **Persistent host identity** at `~/.config/svod/host-id`, recorded as the git committer (author
  stays the agent/UI) so history and the conflict UI can show "edited on machineA vs machineB".
- **`svod-engine clone <remote> <dest> <vaultId>`** CLI subcommand to bootstrap a new machine into an
  existing synced vault.
- **Scheduled auto-backup** (UI-controllable): `backupOnStartup` / `backupIntervalMinutes` /
  `backupOnChange`, with observable `lastBackupAt` / `lastBackupHead`.

### Changed
- Sync subsumes one-way backup: when a vault is synced, the one-way `refs/svod/backup/<vaultId>`
  push is retired (the canonical sync ref is the off-site disaster-recovery copy).
- `POST /api/v1/backup/now` reports "nothing to push / already up to date" as success
  (`ok:true`, `noChange:true`); `ok:false` is reserved for real failures.
- App API **contract 0.12.0** (additive, same major — compatible with existing clients): `syncEnabled`,
  `syncIntervalMinutes`, `syncStatus` (`inSync|syncing|conflicts|offline|error`), `lastSyncedAt`, and
  role `synced` on the sync config / status surface; `POST /sync/now` runs the real cycle.

### Out of scope (documented)
Real-time collaboration, multi-user permissions, a central server, P2P/LAN discovery, CRDTs, and Git
LFS / large binaries (a binary changed on both machines surfaces as a conflict).

## v1.3.0 — App API contract 0.10.0
Embedding ETA/rate, incremental link/tag index, `context_pack` MCP tool, reranker in `/settings`,
filter-only tag browse.

## v1.2.x — App API contract 0.8.0–0.9.0
Non-blocking keyword-first indexing + pluggable embedders, remote-embedder boot robustness,
`POST /embedder/models`, link-graph perf/parse fixes, oversized-chunk fault tolerance.

## v1.1.0 — App API contract 0.7.0
External sources (symlink import, prune, auto-sync).

## v1.0.0 / v1.0.1
First production release; GraalVM `native-image` binaries.
