# Changelog

All notable changes to the Svod engine. The App API contract (`contract/openapi.yaml`) is versioned
independently of the engine; each entry notes the contract version it ships.

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
