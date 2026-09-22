# Design — ideas borrowed from EverOS (EverMind)

Research: `svod-ui-macos/claudedocs/research_evermind_2026-09-22.md`.
Scope: engine 1.25.0 / contract 0.34.0 + svod-ui-macos scripts and palette. Operator decisions (2026-09-22):
the weekly narrative job ships **staged, not loaded**, and covers **all projects**.

## Problems (observed on the live `personal` vault, 2026-09-22)

1. **Captured sessions are written and never read.** 372 notes in `messy/sessions/` (11 MB). The distiller
   (`recall-distill.sh`) is not loaded in launchd; its last draft is from 2026-09-05. `messy/` is excluded from
   search, so no agent sees any of it.
2. **The distiller captures itself.** 19 of the 372 sessions are headless `claude -p` distiller runs: the global
   capture hook records every Claude Code session, including the ones our own scripts start. A job that reads
   sessions and is itself recorded as a session feeds on its own output.
3. **Search degrades silently.** When the query embed fails, `IndexService.semanticLeg` returns an empty list and
   a HYBRID search runs on keywords only and a SEMANTIC search returns nothing (during a model rebuild both
   fall back to keywords); when the reranker fails, `maybeRerank` returns the fused order. Both only
   log. The `/search` response and MCP `search` / `context_pack` look exactly like a healthy result. The same
   happens while semantic search is suppressed during an embedding-model rebuild.

## What we build

### A. `degraded` on every search result (engine + app)
Taken from EverOS 1.3.1, whose benchmark runner rejects any run that contains a degraded step before it reports a
score. For us: the result says which retrieval legs it had to go without.

- `degraded: []` — nothing missing. `["semantic"]` — the caller asked for semantic (HYBRID or SEMANTIC), the engine
  has an active embedder, and the semantic leg failed or was suppressed. `["rerank"]` — the reranker is active and
  failed for this query.
- An embedder configured as `none` is **not** degraded: that is the vault's configuration, not a failure.
- App API `/search` (single vault and `across=true`, where it is the union), MCP `search`, MCP `context_pack`.
- The app shows one line in the ⌘K palette when the list is not empty.

### B. Capture opt-out for our own jobs (app hooks)
`SVOD_CAPTURE=off` in the environment makes `capture-session.sh` exit 0 without posting. `recall-distill.sh` and the
new narrative job set it for the `claude -p` they start. The narrative job also skips sessions that are recognisably
job runs (the distiller's `RUNTIME CONTEXT (this run)` marker and its own marker), which covers the 19 already
captured.

### C. Weekly per-project narrative (app script, staged)
EverOS "Reflection", shaped for Svod:

| EverOS | Svod |
|---|---|
| clusters by vector similarity (0.65) + 7-day window | groups by the `project` a session was captured with — exact, no LLM, no threshold |
| merges a cluster into one episode, `deprecated_by` on the originals | one note per project, `narratives/<slug>.md`; sessions are not modified (they stay in `messy/`, already out of search) |
| incremental: only new fragments are folded in | same: the note stores `covered_until`; only sessions that ended later are sent, together with the current note |
| `reflection_report` audit row | git: every update is a commit through the engine; the frontmatter records `sessions_folded` and `covered_until` |
| weekly, "do not run more often — every merge loses detail" | weekly plist (Sunday 03:00), **not loaded** |
| LLM inside the server | `claude -p` outside the engine; the engine stays LLM-free and only stores the note |

Guards:
- The note is written through `PUT /api/v1/file`, so the engine's secret scanner applies (422 → that project is
  skipped and logged, `covered_until` does not move).
- `<private>…</private>` spans (and everything after an unclosed `<private>`) are removed from session text before
  the model sees it — same rule as the engine's `MarkdownChunker.stripPrivateSpans`. The note is searchable, the
  sessions are not, so this is the one place private text could leak into search.
- `<private>` handling also skips a session note marked `private: true` as a whole.
- `claude -p` runs with `--tools ""`, `--setting-sources ""`, its own `--system-prompt` and an empty temporary cwd:
  text in, text out, and nothing else in the model's context. That avoids both failures the distiller hit (a
  headless agent cannot read outside its cwd; the context-mode hook intercepts curl). Run from the repo, the first
  real run pulled CLAUDE.md, auto-memory and git status into the model and the note cited a commit hash found in
  no session (adversarial verifier, 2026-09-22). `--bare` would isolate more but accepts only an API key, not the
  OAuth login `claude` uses here.
- Bounded input: newest sessions first up to a byte budget per project, then presented oldest-first. Sessions that
  did not fit in a first run are not folded later — the note describes the current state, which is what EverOS's
  merge prompt also ends on.
- At least 2 new sessions before a project is touched.

## Not built (and why)

- `/ready` provider state: the app already shows `EmbeddingStatus` in Settings → Indexing; the case that is not
  visible anywhere is a single degraded search, which A covers.
- Cases → SKILL.md drafts: needs the narratives first to see what actually repeats.
- "Last session" line at session start: `continuity` covers it better.
