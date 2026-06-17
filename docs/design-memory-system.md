# Design: Memory-system primitives for Svod

**Status:** approved scope (sprint). **Source of ideas:** `claudedocs/research_rag-to-memory-systems_20260617.md` (Oracle "From RAG to Memory Systems"). **Sprint:** `/sprint-orchestrate full`.

## Forcing questions

- **Who needs this & what do they do today?** Agents (via MCP) and the user use Svod as an auditable KB. Today every note is one undifferentiated corpus; an agent that "remembers" a fact writes a plain note that ranks alongside everything else and never expires — the article's named anti-pattern. Durable rules (preferences/policies) can't be loaded deterministically every turn; they compete in top-k like any note.
- **Narrowest MVP someone would pay for?** (1) Tell memories apart by a frontmatter `type`; (2) load a set of notes *in full, deterministically* ("the rule book") instead of hoping ranking surfaces them; (3) hide revoked/superseded/expired/provisional memories from recall by default; (4) a single `remember` tool that gates what becomes durable (dedup + status).
- **What makes someone say "whoa"?** An agent writes `remember(type=preference, "user wants terse answers")` once; from then on every `context_pack(enumerate, type=preference)` returns it verbatim, every session, no ranking lottery — and a contradicting fact *supersedes* the old one instead of both lingering. Memory that compounds and self-cleans, all still plain diffable git.
- **How does it compound?** Each typed, gated memory is reusable across sessions/agents; provisional→active + supersession + expiry keep the recall layer clean as it grows, so quality rises with volume instead of degrading (no "all assistants feel the same after the first chat").

## Scope (locked: full incl. write-gate tool)

1. **Memory typing** — reserved frontmatter `type` (free-form; conventions: `policy|preference|fact|episode|note`), indexed + filterable.
2. **Path A enumeration** — a `context_pack` `enumerate` mode: return *all* notes matching a filter, **in full, unranked, no top-k** (the deterministic rule book). Complements existing semantic `context_pack` (Path B).
3. **Lifecycle filters** — frontmatter `status` (`active|provisional|revoked`, default treated as active), `superseded_by`, `expires_at`; honored as retrieval filters (default: hide revoked, provisional, superseded, expired — opt-in to include).
4. **`remember` MCP promotion-gate tool** — classify+scope, dedup by content hash, type-driven status (fact/policy→`provisional`, preference/episode→`active`), optional supersession. MCP tool #13→#14.

**Deferred (not this sprint):** episodic distillation/summarization (#5) — new ground (consolidation), larger effort.

## Non-negotiable constraints (Svod identity)
- **Everything stays plain markdown + frontmatter in git.** No new datastore. Lifecycle/typing are frontmatter conventions + Lucene filter fields, nothing more.
- **Backward compatible.** A note with none of these frontmatter keys behaves exactly as today (fully visible, untyped). The defaults must not hide existing notes.
- **Don't adopt** from the article: SQL backend, multi-tenant `tenant_id/user_id` rows (vault = scope), the linear 0.4/0.6 fusion (Svod's RRF+reranker is better).
- Keep `git`-as-substrate auditability: a "forgotten" memory is `revoked`/`expired` (filtered out), never destroyed — history + restore still work.

## Acceptance (high level)
- An agent can type a memory and retrieve it by type; a revoked/superseded/expired note disappears from search/context_pack by default but is still readable directly and via `includeAll`.
- `context_pack enumerate` returns every matching note's full content, deterministically ordered, regardless of relevance/budget.
- `remember` dedups identical content, sets status by type, and can supersede a prior memory.
- Existing notes/tests unaffected; contract bumps minor (0.13.0 → 0.14.0).
