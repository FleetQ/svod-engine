# Requirements — multi-vault + Obsidian import

> Status: **requirements discovery** (output of `/sc:brainstorm`). Not a design. The "how"
> (engine topology, link syntax, schema) is deferred to `/sc:design`.
> Date: 2026-06-12.

## Context & clarified goals

The user runs two Obsidian vaults today — one **personal**, one **work** — and wants Svod to
(a) ingest existing Obsidian content and (b) represent the two-vault split natively.

Discovery answers that constrain the solution:

| Question | Answer | Consequence |
|---|---|---|
| Why separate vaults? | **Different environments** (own sync remote / possibly own agents) — *not* a hard secrecy boundary | Vaults are isolated *units of sync + agent scope*, but reads across them are permitted |
| Import nature | **One-shot migration** | No bidirectional live bridge needed; Obsidian may remain only as an editor on the same git tree |
| Non-markdown attachments | **Yes, important** | Import must carry binaries (images, PDF); links to them must keep resolving |
| Cross-link personal ↔ work | **Yes, sometimes** | The link graph must span vault boundaries |

### The load-bearing tension (resolved)

"Different environments" + "cross-link sometimes" rules out both trivial options:

- A **single flat vault** with `personal/` + `work/` folders → can't give per-vault sync remotes.
- **Fully isolated** vaults (two unrelated repos) → a `[[personal note]]` inside a work note
  can't resolve.

Because the split is *environments, not secrecy*, cross-vault links are **allowed** — which is
exactly what makes them implementable. The model this points to: **multiple named vaults, each
its own git repo + sync remote + agent scope, unified by one link graph that resolves
cross-vault links** (e.g. a qualified `[[work:project]]`). Concrete syntax/topology → `/sc:design`.

---

## Functional requirements

### Multi-vault
- **FR-V1** The engine manages **N named vaults** (e.g. `personal`, `work`), each a distinct git
  repository with its own working tree.
- **FR-V2** Each vault has **its own sync remote(s)** and sync role (merge authority / replica),
  configured independently. Personal and work sync to different servers.
- **FR-V3** Agents are **scoped to one or more vaults**. An agent token grants access to a named
  set of vaults; an agent acts only within its granted vaults. (Not a secrecy wall — see NFR-S1
  — but the default scope is explicit, not "all vaults".)
- **FR-V4** Search, tree, graph, history, metrics are **addressable per vault**, and optionally
  **across all vaults the caller can see** (a unified query).
- **FR-V5** The wikilink graph **resolves cross-vault links**. A note in `work` may link a note
  in `personal`; backlinks surface across the boundary.
- **FR-V6** Moving/renaming a note that is **cross-linked from another vault** rewrites those
  backlinks too — link integrity holds across vaults (one logical operation, even if it spans
  two repos).
- **FR-V7** A client (UI/API) can **list vaults**, see per-vault status (connected, sync, agent
  activity), and switch active context.
- **FR-V8** Each vault keeps the existing single-writer integrity guarantees independently (one
  serialized writer *per vault*; a stall in one vault never blocks another).

### Obsidian import
- **FR-I1** Import an Obsidian vault as a **one-shot migration into a target Svod vault** (the
  existing `ObsidianImport.import(source, engine, into=…)` is the core; it preserves frontmatter
  and `[[wikilinks]]`, skips `.obsidian/`, and commits each file with an attributed author).
- **FR-I2** Import must be **triggerable** by the user — today the logic exists but is wired to
  no entrypoint. Surface it via at least one of: CLI subcommand, App API endpoint, MCP tool.
  (Which surface(s) → `/sc:design`; loopback App API is the natural fit for a UI "Import" button.)
- **FR-I3** Import **carries non-markdown attachments** (images, PDF, etc.), not just `.md` — the
  current `collectMarkdown` is markdown-only and must be extended. Attachments are committed so
  that `![[image.png]]` / `[link](file.pdf)` references keep resolving after migration.
- **FR-I4** Import is **idempotent / re-runnable** without duplicating or clobbering: a second
  run reconciles rather than creating `note (1).md` (optimistic-revision aware).
- **FR-I5** Import **reports a result** (imported / skipped / failed counts and paths) so the user
  can verify the migration, matching the existing `Result(imported, skipped)` shape.
- **FR-I6** Importing the user's two Obsidian vaults yields the two Svod vaults — personal vault →
  Svod `personal`, work vault → Svod `work`.

---

## Non-functional requirements

- **NFR-S1 (trust model)** Vault separation is **organizational + per-environment, NOT a hard
  security boundary.** Do not over-build secrecy enforcement (encryption-per-vault, mandatory
  access control). Agent scoping (FR-V3) is about clarity and blast-radius, not defeating a
  malicious work agent from reading personal notes. *If this ever needs to become a hard
  boundary, that is a separate, larger effort — flag, don't pre-build.*
- **NFR-S2 (integrity preserved)** All current invariants (single writer, atomic writes, git
  history, optimistic concurrency, crash recovery) hold **per vault**, unchanged.
- **NFR-P1** Adding vaults scales linearly; per-vault indexes/writers are independent. No global
  lock across vaults on the read path.
- **NFR-C1 (contract)** Multi-vault is an **additive, versioned contract change** (likely vault
  as a path segment or parameter). Single-vault clients must keep working (back-compat or a clean
  major bump — decide in `/sc:design`).
- **NFR-L1 (no lock-in, unchanged)** Each vault stays a plain git repo of Markdown + attachments;
  export is still `git clone`, per vault.

---

## User stories / acceptance criteria

- **US-1** *As the owner, I migrate my personal Obsidian vault into Svod in one action.*
  AC: notes + attachments + wikilinks land in the `personal` Svod vault; `.obsidian/` skipped;
  every file is an attributed commit; a result report lists counts; re-running doesn't duplicate.
- **US-2** *As the owner, I keep work and personal as separate environments.*
  AC: `work` and `personal` are separate git repos with separate sync remotes; syncing work does
  not touch personal; each shows its own status in the UI.
- **US-3** *As the owner, I occasionally link a work note to a personal note.*
  AC: `[[…]]` across vaults resolves; the target's backlinks show the cross-vault reference;
  moving either note keeps the link intact.
- **US-4** *As a work agent, I only see the work vault by default.*
  AC: an agent scoped to `work` lists/searches/writes only `work`; granting `personal` is an
  explicit config change. (Clarity/scoping, not a secrecy guarantee — NFR-S1.)
- **US-5** *As the owner, I search across both vaults at once when I want to.*
  AC: a unified query returns hits from every vault the caller can see, each tagged with its vault.

---

## Open questions (for `/sc:design`)

1. **Engine topology:** one engine process hosting N vaults, vs one engine per vault with a thin
   router in front. (Cross-vault links + one UI lean toward one engine, N vaults.)
2. **Cross-vault link syntax:** qualified `[[work:note]]` vs an alias/registry vs path-based.
   How does an *unqualified* `[[note]]` resolve when the same basename exists in two vaults?
3. **Cross-vault link integrity across two git repos:** a rename in vault A must rewrite a
   backlink in vault B — this is a two-repo transaction. How is atomicity defined when the unit
   of durability (git commit) is per-repo?
4. **Unified vs per-vault index:** one Lucene index with a `vault` field, vs an index per vault
   fanned-in at query time.
5. **Contract shape:** vault as a path prefix (`/api/v1/{vault}/…`), a query/header, or a
   default-vault + explicit override. Back-compat vs major bump.
6. **Attachment handling in the index/graph:** binaries aren't embedded, but must be tracked as
   graph nodes so `![[img.png]]` resolves and a missing attachment is flagged.
7. **Import idempotency mechanism:** content-hash reconcile vs a manifest of prior imports.
8. **Default vault & MCP scoping config:** how an agent's vault grant is expressed in config and
   surfaced in the audit log.

## What NOT to build (scope guard)

- No bidirectional live Obsidian sync (user chose one-shot migration).
- No per-vault encryption / hard access-control wall (NFR-S1).
- No cross-vault *merge* semantics — vaults sync independently; only the *link graph* spans them.
