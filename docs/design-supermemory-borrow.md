# Design — memory review and the Supermemory borrow (engine v1.23.0, contract 0.33.0; app v0.2.25)

**Status:** approved scope (sprint, operator delegated the choice of ideas). **Research:**
`svod-ui-macos/claudedocs/research_supermemory_2026-09-17.md`.

## Think

**Who needs this and what do they do today?** Agents call `remember(type=fact|policy)` all day; the
engine stores those as `status: provisional`, and the default recall filter hides provisional notes.
The operator is supposed to confirm them. Today there is **no way to confirm one** short of opening the
file and hand-editing `status:` — the MCP `promote` tool only moves a draft out of `messy/`, it never
touches `status` [observed: `SvodTools.promote` → `engine.promote(from, to)`]. Measured on the live
`personal` vault on 2026-09-17: **98 provisional vs 40 active** memory notes, added steadily since June
(7 / 23 / 52 / 16 per month). Most of what agents were told to remember is invisible to `search` and
`context_pack`.

**Narrowest MVP?** A review queue: list provisional / needs-review memories, approve (→ active) or
decline (→ revoked), undo with reopen. Everything the actions need already exists as frontmatter
semantics; only the endpoint and the screen are missing.

**What makes someone say "whoa"?** Open the app, see "98 awaiting review", clear them in a few minutes,
and the next Claude session starts with the confirmed rule book already in context.

**How does it compound?** Every approved memory becomes recallable and enters the session-start rule
book; declined ones stop polluting classification (a revoked memory is skipped as a comparison target).

## Scope (in)

| # | Item | Repo | Source |
|---|---|---|---|
| S1 | `GET /api/v1/memory/review`, `POST /api/v1/memory/review` (approve / decline / reopen) + `awaitingReview` on the dashboard | engine | Supermemory memory-review queue; fixes the provisional dead end |
| S2 | `needs-review` indexed as a term so the queue also finds non-provisional UNCERTAIN memories | engine | S1 completeness |
| S3 | `remember` gains optional `expiresAt` (ISO date or instant, must be in the future) → `expires_at` | engine | `expires_at` was read and filtered but had no writer |
| S4 | MCP: server-level `instructions`; descriptions of `promote`, `search`, `list`, `remember` say when NOT to use them and what they do not do | engine | Supermemory tool descriptions; `promote` wording is what made the dead end easy to miss |
| S5 | `GET /api/v1/memory/rulebook` — active policy/preference index (path, title, type, subject, one-line summary) + awaiting-review count | engine | Supermemory session-start profile, but deterministic (no ranking) |
| S6 | Review UI: Settings → Memory "Awaiting review" section; approve/decline in the editor's memory badge bar; `needs-review`, `contradicts`, `supersedes` badges | app | S1 client |
| S7 | Session-start hook that injects the rule-book index; capture hook derives `project` from the git remote; installer that wires both hooks globally in `~/.claude/settings.json` | app (`.claude/hooks`, `Scripts/`) | Supermemory plugin session-start + container-tag |
| S8 | Distiller prompt: explicit keep / skip categories | app (`.claude/hooks/recall-distill-prompt.md`) | Supermemory `AGENT_ENTITY_CONTEXT` |

## Out of scope (and why)

- **Auto-recall on every prompt** — dropped on 2026-09-15 with measurements; nothing new here changes that.
- **Changing the fact/policy → provisional default.** The queue makes the gate usable; removing the gate
  is a separate product decision for the operator, reversible later.
- **An MCP tool that approves memories.** The gate exists so an agent cannot confirm its own memory.
  Approval stays with a person (App API).
- **`derives` / `extends` relations, dreaming, container tags, profile buckets, MemoryBench provider** — see research doc.
- **Re-indexing existing notes for S2.** A schema bump forces a full re-embed (hours on bge-m3). Both
  `needs-review` notes that exist today are also `provisional`, so the queue already finds them; the new
  term is written for every note indexed from now on.

## Decisions

- **D1 Review actions are frontmatter rewrites through the engine's guarded write**, committed with the
  person's identity — git is the audit trail and undo. Approve: `status: active`, drop `needs-review`.
  Decline: `status: revoked`, drop `needs-review`. Reopen: `status: provisional` (undo of either).
  Every action stamps `reviewed_at` (ISO instant) and `reviewed_by` (principal name).
- **D2 Only memory notes are reviewable**: the file must carry a `status` or `type` frontmatter key and
  must not be under `messy/sessions/`. Anything else → 400. A missing path → 404. A stale
  `expectedRevision` → 409 with the usual conflict body.
- **D3 Superseded memories are not reviewable** (they are already replaced) → 409 `superseded`.
- **D4 Queue order:** `needs-review` or `contradicts` first (those need a human most), then newest
  `created` first, then path. Limit default 200, max 500. `total` is the full count.
- **D5 Excerpts and rule-book summaries mask `<private>` spans; `private: true` notes are never listed** —
  same rule as every recall surface.
- **D6 Feature detection** in the app on `apiVersion >= 0.33.0`; older engines hide the section.
- **D7 Hooks are best-effort**: never block a session, exit 0 on every path, silent when the engine is
  down. The session-start hook prints a short notice only on 401/403 (a bad key must not look like
  "no memories" — the Supermemory `/status` lesson).
- **D8 Session-start injection is an index, not full text**: at most 40 lines / 6,000 characters, each
  line `- [type] title — summary (path)`. The model reads a note on demand. This bounds the per-session
  context cost that sank auto-recall.
