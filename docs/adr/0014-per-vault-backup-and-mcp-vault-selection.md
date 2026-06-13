# ADR-0014 — Per-vault backup remotes + per-request MCP vault selection

- Status: **Accepted**
- Date: 2026-06-13
- Scope: The two deferred code items from ADR-0011/0013 — backup per environment, and letting a
  multi-grant agent reach every granted vault.

## Decisions

### 1. Per-vault backup remotes
Backup moves from one global remote to **per vault**: each `VaultSettings` may carry its own
`backup` (and a runtime `PUT /api/v1/settings/backup?vault=<id>` sets/persists one vault's remote
to that vault's `.svod/backup.json`). `BackupService` holds a `Binding` per vault (id, repo root,
config, store); `backupNow(vaultId)` pushes that vault's canonical branch to **its own** remote
under `refs/svod/backup/<vaultId>`; `backupAll()` does every configured vault. This matches the
"different environments" model — a work vault backs up to the company server, a personal vault to
your own. Effective remote per vault = persisted ?? per-vault `VaultSettings.backup` ?? global
`config.backup` (back-compat: a single global remote still backs up every vault).
- `POST /api/v1/backup/now?vault=`, `GET /api/v1/sync/config?vault=`, `PUT /settings/backup?vault=`
  are all vault-scoped (default vault when omitted). `BackupAck.status` gained `error` (a push
  failure); credentials stay redacted everywhere and resolved through `Secrets` only at push time.

### 2. Per-request MCP vault selection (multi-grant agents reach all granted vaults)
Previously an agent's MCP session bound to its **first** granted vault (a documented limitation,
ADR-0011 §6, surfaced by a startup warning). Now every per-vault tool's schema carries an optional
`vault`; a call resolves its target = `vault` arg ?? the agent's primary vault, and the MCP layer
**enforces the grant**:
- target in the agent's grant → routed to that vault's tools;
- target **not** granted → `denied` (`isError`);
- empty grant (single-vault / back-compat agents) → unrestricted, default vault.
So a `[work, personal]` agent reaches both by naming the vault, a `[work]` agent is denied
`personal`, and the old startup warning is removed (the limitation is gone). `SvodMcpServer` now
takes a `(vaultId) -> SvodTools?` resolver + the default vault id instead of a per-agent binder; the
single-vault convenience constructor is unchanged, so existing MCP tests pass untouched.

## Consequences

- The "different environments" model is now complete end to end: separate sync remotes (ADR-0011),
  separate backup remotes (here), and agents that can be scoped to one vault or span several.
- Contract is unchanged in shape (additive `?vault=` on existing ops routes, one enum value); no
  version bump beyond 0.4.0.

## NOT done (owner decision, not code)

- **License** — `Apache-2.0` remains "proposed, pending owner confirmation". This is the repository
  owner's call to make; nothing in the engine can settle it.
- **Encryption-at-rest** — reconfirmed out of scope for this deployment (relies on disk encryption).
