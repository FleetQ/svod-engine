# Architecture — multi-vault + Obsidian import

> Input: [requirements-multivault-import.md](requirements-multivault-import.md). Resolves the
> open questions into concrete design. Build order + gates below. Contract bump 0.2.0 → 0.3.0.

## Load-bearing decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **One engine process, N vaults.** A `VaultManager` holds `Map<vaultId, VaultContext>`; each context is the *existing* per-vault assembly (engine + index + conflicts + sync). | Cross-vault links + one UI need a single process. Reuses all per-vault integrity machinery unchanged. |
| D2 | **Per-vault index, federated at query time.** Each vault keeps its own `IndexService`; cross-vault search fans out and merges. | Reuses `IndexService` as-is; no Lucene schema rework. |
| D3 | **Config back-compat.** New `vaults: [{id, path, sync…}]`; legacy single `vaultPath` is synthesized into one default vault. `defaultVault` = first if unset. | Every existing config + the 91-test suite + web-viewer keep working. |
| D4 | **App API: additive vault selection.** Optional `?vault=` on per-vault routes (default = defaultVault) + new `GET /api/v1/vaults` + `POST /api/v1/import`. | Additive (minor bump). Single-vault clients unchanged. |
| D5 | **Qualified cross-vault links `[[vault:note]]`.** Unqualified `[[note]]` resolves within the owning vault only (no cross-vault guessing). | Unambiguous; same basename in two vaults can't silently cross. |
| D6 | **Agent→vault scoping (clarity, not secrecy).** `AgentSettings.vaults` grant; empty ⇒ [defaultVault]. A tool call names a vault; ungranted ⇒ `denied`. | Matches "different environments, not a hard boundary" (NFR-S1). Blast-radius, not a security wall. |
| D7 | **Cross-vault rename = best-effort, per-repo commits, NEVER lose data.** In-vault backlinks rewrite atomically (existing). Backlinks from *other* vaults rewrite via their own writer as separate commits. No cross-repo atomicity (git can't). A failed cross-vault rewrite leaves the link *unresolved*, not lost. | Honest: git's durability unit is one repo. Invariant "never lose files" still holds — nothing is deleted; worst case a stale link shows unresolved and is fixable. |
| D8 | **Binary-safe write path.** Engine gains `writeBytes(path, ByteArray, …)` through the same actor; `write(String)` wraps it. Binaries are committed + tracked as graph/tree nodes but **not** chunked/embedded. | Attachments (FR-I3) can't round-trip through a `String`. Single-writer + atomic guarantees extend to binaries for free. |
| D9 | **Import surfaces: App API + CLI** (not MCP). One-shot, idempotent, into a chosen vault. | A local FS import is a loopback/operator action; a remote agent shouldn't drive it. |

## Components

### VaultContext (new) — the per-vault unit
Holds what `SvodNode.start` currently builds per vault: `id`, `name`, `SvodEngine`, `IndexService`,
`ConflictStore`, optional `SyncEngine`/`SyncGit`, `FileWatcher`. Owns its lifecycle (open/close).

### VaultManager (new) — the registry + router
- Opens all configured vaults at start (each acquires its own exclusive lock — single-instance
  per vault is preserved). Closes them in reverse on shutdown.
- `vault(id): VaultContext` / `default()` / `ids()`.
- `federatedGraph()`: builds a `FederatedLinkGraph` across all vaults (for cross-vault backlinks).
- `searchAcross(grantedIds, query)`: fan-out per-vault search, merge by score, tag each hit
  with its vault.
- `crossVaultRelink(movedVault, fromPath, toPath)`: after an in-vault move, rewrite qualified
  backlinks living in *other* vaults via their writers (D7).

### FederatedLinkGraph (new)
Builds each vault's `LinkGraph`, plus a cross-vault resolution layer: a `[[work:note]]` in
`personal/x.md` becomes an edge `personal:x → work:note` and a backlink on `work:note`.
Unqualified links resolve within their own vault only.

### SvodNode (refactor)
Becomes the orchestrator: builds a `VaultManager` (N contexts), ONE shared `EventBus` (events
gain a `vault` field), ONE `AgentRegistry` (specs carry vault grants), ONE MCP server, ONE App
API — both routing to vaults via the manager. Per-vault watchers. Shutdown drains every vault.

### Config (extend)
```
SvodConfig(
  vaults: List<VaultSettings> = [],     // new; each {id, path, syncRemotes, hostId, mergeAuthority, syncIntervalSeconds}
  vaultPath: String? = null,            // legacy; synthesized to one vault if `vaults` empty
  defaultVault: String? = null,         // defaults to first vault
  host, appApiPort, mcpPort, embedder, agents, secretScanning, mcpTls, webViewerPath  // shared
)
AgentSettings(… , vaults: List<String> = [])   // grant; empty ⇒ [defaultVault]
```
`validate()` additions: ≥1 vault resolvable; unique vault ids; agent grants reference real vaults;
defaultVault exists.

### App API (extend, additive)
- `?vault=<id>` optional on: tree, file (GET/PUT/DELETE), file/*, search, graph, tags,
  index/status, history, diff, revision, links, conflicts, conflicts/resolve. Omitted ⇒ default.
- `GET /api/v1/vaults` → `[{id, name, default, sync}]`.
- `GET /api/v1/search?...&across=true` → federated search across all vaults.
- `POST /api/v1/import` → `{source, vault?, into?}` → import result.
- Events carry `vault`.

### MCP (extend)
- `AgentIdentity` carries granted vault ids. Every tool accepts an optional `vault` (default =
  agent's first grant); ungranted vault ⇒ `denied` (audited). `list_vaults` tool added.

## Build order & gates

- **Gate 1 — Import + binary write path.** Engine `writeBytes`; import all files (md=text,
  others=bytes) with idempotency (unchanged/imported/skipped); App API `POST /import` + CLI
  `--import`. Index skips embedding binaries. *Self-contained, shippable.*
- **Gate 2 — Multi-vault core.** Config (back-compat), `VaultContext` + `VaultManager`, SvodNode
  refactor, App API `?vault=` + `/vaults`, per-vault sync. **The whole existing suite must stay
  green via the synthesized default vault.**
- **Gate 3 — Federation + scoping + cross-vault rename.** `FederatedLinkGraph`, qualified links,
  MCP agent→vault scoping, cross-vault relink (D7), federated search.
- **Gate 4 — Review + full suite + ADRs (0011 multi-vault, 0012 import) + commit.**

Gate rule (unchanged spirit): do not advance past Gate 2 until the back-compat suite is green —
multi-vault must not regress single-vault integrity.

## What we explicitly do NOT build (scope guard)
- No bidirectional live Obsidian sync (one-shot migration).
- No per-vault encryption / hard access-control wall (NFR-S1).
- No cross-repo atomic transactions (D7 documents the best-effort boundary).
- No cross-vault *content* merge — only the link graph spans vaults.
