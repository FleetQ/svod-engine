# ADR-0016: External sources and symlink import

- Status: **Accepted**
- Date: 2026-06-13
- Supersedes/relates: ADR-0001 (single-writer), the Obsidian import path (ADR-0011..0014 area)

## Context

A real Obsidian vault often isn't self-contained: notes and whole folders are **symlinks** into other
projects (single source of truth lives in each project; the vault is an aggregated view). Two problems
follow for a git-backed store:

1. **Import crashed on symlinks.** `Files.walkFileTree` doesn't follow symlinks, so a directory symlink
   was handed to `readAllBytes` → `IOException "Is a directory"` → the whole import 500'd.
2. **A symlink can't live in a git tree.** It points outside the repo; committing the link is
   meaningless on another machine. We must copy content, not link — and copying once loses the "stays
   in sync" property the symlink gave.

## Decision

### 1. Import: skip symlinks by default, `followSymlinks` to materialize (one-shot)

Import skips symlinks by default (each recorded in `skipped`) and never aborts on a bad entry
(unreadable / loop / permission → recorded, walk continues; only a missing `source` is fatal → 400).

`POST /api/v1/import {followSymlinks:true}` instead **materializes**: file links are copied as files,
directory links are descended into and their contents copied under the link's path. The walker detects
loops (`FileSystemLoopException` → recorded) and links may point outside the source root (intended).
This is the **one-shot migration** path for a symlink-built vault.

### 2. External sources: registered paths that re-sync (repeatable)

A vault can register **external sources** — a file or directory outside the vault, mapped to a vault
subpath, that can be **re-synced** on demand. This is the durable, git-friendly replacement for a
symlink: we copy and can re-copy.

- Model: `ExternalSource{id, path, into, followSymlinks, lastSyncedAt}`, persisted in the gitignored
  `<vault>/.svod/sources.json`. `id` is derived deterministically from the absolute path
  (re-registering the same path is idempotent).
- Endpoints (per vault, `?vault=`): `GET/POST /api/v1/sources`, `DELETE /api/v1/sources/{id}`,
  `POST /api/v1/sources/{id}/sync`, `POST /api/v1/sources/sync`.

**Conflict semantics — external-wins-unless-locally-edited.** A per-source manifest (vault path → git
blob id at last sync) distinguishes the cases:

| State | Action |
|---|---|
| vault path absent | **create** |
| vault content == external | **unchanged** |
| differ, vault still == manifest (only external changed) | **update** (external edit flows in) |
| differ, vault != manifest (vault edited since last sync) | **conflict** — left as-is, never clobbered |
| in manifest, gone from source | **orphaned** — reported, left in the vault |

This matches the engine's existing never-clobber + surface-conflict philosophy (host-to-host sync,
import). It is *not* the import rule (import never updates after first copy); a source is a managed
mirror, so external edits must flow — but only onto an untouched vault copy.

Writes go through one overwriting batch commit per sync (`writeBatch(..., overwrite = true)`); the
overwrite only fires for paths already classified create/update, so the conflict guard is upstream of
it. Secret-scanner-blocked `.md` entries are reported in `skipped`.

### Deletion propagation (`prune`) — implemented

A source can opt into `prune` (off by default — deletion is destructive). When set, a file gone from
the source is **soft-deleted** from the vault (`deleted` in the result), but only if the vault copy is
still untouched since the last sync; a locally-edited copy is left as `orphaned` (never deleted),
mirroring the update-vs-conflict guard. Deletes are soft (git trash), so always recoverable.

### Automatic scheduling — implemented

`SourceScheduler` (config `sourceSync: {onStartup, intervalMinutes}`) re-syncs every source of every
vault: once at startup if `onStartup`, then on `intervalMinutes` cadence (>0). Both off ⇒ no
scheduler (sources sync only on an explicit endpoint call). A failed round is logged and the loop
continues; the scheduler is cancelled first in the node's graceful shutdown.

### Still deferred

- **Race window.** Classification reads and the batch write are separate write-actor submissions; a
  local edit landing between them could be overwritten. Acceptable (sources are synced when idle); a
  fully optimistic per-file path would trade one-commit-per-sync for safety here.

## Consequences

- A symlink-built Obsidian vault imports in one shot (`followSymlinks:true`), and the external folders
  it pointed at can be kept current via registered sources — no symlink ever enters git.
- Contract `0.5.1 → 0.6.0` (additive: new `ops` endpoints + schemas). Same major ⇒ compatible with
  existing 0.5.x UI clients.
- One small engine addition: `writeBatch(overwrite)` + a public `blobId(bytes)` helper. The default
  (non-overwriting) batch path — and thus import — is unchanged.

## Alternatives considered

- **Resolve symlinks by default on import** — rejected: silently following links out of the chosen
  folder is a footgun; opt-in is safer and the source-sync model is the durable answer anyway.
- **External-always-wins (source-owned subtree)** — rejected: silently clobbers local edits. The
  manifest costs little and protects hand edits while still flowing external changes.
- **Never-clobber for sources (like import)** — rejected: external edits would never flow after the
  first sync, so it isn't sync.
