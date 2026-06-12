# ADR-0011 — Multi-vault: N vaults in one engine, federated links, per-agent scoping

- Status: **Accepted**
- Date: 2026-06-12
- Scope: Multi-vault support (personal + work, etc.) — requirements in
  `docs/requirements-multivault-import.md`, design in `docs/architecture-multivault-import.md`.

## Context

Users run multiple Obsidian vaults (e.g. a personal and a work vault) and want Svod to represent
that split natively — separate sync remotes and agents per vault — while still occasionally
linking a note in one vault to a note in another. Discovery established the split is **"different
environments," not a hard secrecy boundary**, which is exactly what makes cross-vault links
implementable rather than contradictory.

## Decisions

### 1. One engine process, N vaults (not engine-per-vault)
A `VaultManager` holds `Map<vaultId, VaultContext>`. Each `VaultContext` is the *existing*
per-vault assembly — its own `SvodEngine` (exclusive lock), `IndexService`, `ConflictStore`,
`FileWatcher`, optional `SyncEngine`. This **reuses all per-vault integrity machinery unchanged**;
multi-vault adds routing, not new write paths. Cross-vault links and a single UI both need one
process. Single-instance is still per vault (each engine acquires its own lock).

### 2. Config back-compat via a synthesized default vault
New `vaults: [{id, path, sync…}]` + `defaultVault`. A legacy single `vaultPath` is synthesized
into one default vault (folding in the legacy sync fields). **Every pre-existing config and the
entire prior test suite keep working** — the 91→106 test growth is purely additive; nothing
single-vault regressed.

### 3. App API: additive `?vault=` routing (minor bump 0.2.0 → 0.3.0)
Every per-vault route accepts an optional `vault` query param (default vault when omitted; unknown
id ⇒ 404). New `GET /api/v1/vaults`. Federated search via `?across=true` tags each hit with its
vault. Additive — single-vault clients (and the web viewer) are untouched. The server is decoupled
from the lifecycle layer via `VaultRouter`/`VaultView` interfaces, so tests and simple embeds use a
single-vault convenience constructor.

### 4. Qualified cross-vault links `[[vault:note]]`
`FederatedLinkGraph` resolves a **qualified** `[[work:project]]` into the named vault and surfaces
the cross-vault backlink on the target (`GET /api/v1/file/links` → `crossVaultBacklinks` as global
ids `"vault:path"`). An **unqualified** `[[note]]` resolves only within its own vault — no
cross-vault guessing, so an identically-named note elsewhere is never silently matched.

### 5. Cross-vault rename = best-effort, per-repo commits, never lose data (the honest boundary)
Moving a note rewrites in-vault backlinks atomically (existing `moveWithLinks`, one commit). A
qualified `[[work:project]]` reference living in *another* vault is rewritten via **that vault's
writer as a separate commit**. There is **no cross-repo atomicity** — git's unit of durability is
one repo, so we don't pretend otherwise. A failed/raced cross-vault rewrite leaves the move intact
and the stale link merely *unresolved* (it still shows as a cross-vault backlink to fix) — never a
lost file. Invariant 4 holds: each rewrite is an optimistic write, never a silent overwrite.

### 6. Per-agent vault scoping by construction (clarity, not a security wall)
An agent grant (`agents[].vaults`; empty ⇒ default vault) binds its MCP session to that vault's
tool set: an agent scoped to `work` is handed only work's tools and **cannot reach another vault**
— isolation by construction, no per-call denial needed. Per NFR-S1 this is organizational /
blast-radius scoping, **not** a hard secrecy boundary; we deliberately did **not** build per-vault
encryption or mandatory access control. (Known limit: an agent granted multiple vaults binds to
its *first* grant; switching vaults mid-session is a future increment.)

### 7. Binary-safe write path for attachments
The engine gains `writeBytes`/`readBytes` through the same single-writer actor, with the same
atomic + optimistic guarantees. Binaries are committed and tracked but **not** embedded (the index
filters to `.md`). This is what lets import carry images/PDFs (ADR-0012).

## Consequences

- Per-vault index, federated at query time (fan-out + merge) — no Lucene schema rework.
- The link graph spans vaults; sync, conflicts, and merges stay strictly **per vault** (no
  cross-vault content merge).
- Contract grew additively; `ApiCompatibility` major unchanged ⇒ existing clients compatible.

## Alternatives rejected

- **One flat vault with `personal/`/`work/` folders** — can't give per-vault sync remotes.
- **Fully isolated vaults (two unrelated processes/repos)** — cross-vault links can't resolve.
- **Engine-per-vault + a router process** — more moving parts; cross-vault graph + one UI argue
  for a single process.
- **Cross-repo two-phase commit for renames** — not achievable on git; best-effort + "never lose
  data" is the honest contract (Decision 5).
