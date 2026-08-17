# Svod — hardening sprint (design)

**Origin:** `claudedocs/research_svod-improvements_2026-08-17.md` — an internal survey run against the
live engine after the incremental-attachment release (v1.17.0 / contract 0.26.0).

## Who needs this, and what are they doing today?

The operator, running Svod as their daily memory system across three vaults, plus the agents that
write into it through MCP. Today they:

- **watch the thematic map drift with no mechanism to stop it.** v1.17.0 attaches new notes between
  builds and explicitly bets on "a periodic full rebuild restores the truth" — and there is no
  scheduler for it. The bet has no counterparty;
- **read summaries written for the wrong level.** 38 summaries exist, all at the coarsest level
  (median 44 notes, largest 320) written from under ten notes each, while 258 precise level-0 themes
  (median 7 notes — small enough to summarise honestly) have none;
- **debug integration errors that present as decoding failures**, because unknown `/api/...` paths
  return `200 text/html` from the web-viewer SPA fallback. This has cost this project time twice;
- **accumulate captured sessions nothing consumes** — 9 captured, 0 distilled, 0 notes written;
- **carry up to six hours of unbacked agent writes** (`backupIntervalMinutes: 360`,
  `backupOnChange: false`).

## What is the narrowest thing worth shipping?

Two defects and one missing mechanism:

1. `404` for unknown `/api` routes — a few lines, removes a whole class of client confusion;
2. `GraphScheduler` + a drift measure — makes v1.17.0's stated trade actually hold;
3. hierarchical summarisation **behind a flag, default off** — the code path plus tests, without
   spending 2–4 hours of Ollama time in this sprint.

Everything else in the sprint is configuration, scripts, or tests around existing behaviour.

## What would make someone say "whoa"?

That a 320-note theme's summary can be composed from summaries of *all* its members rather than from
eight raw excerpts — the same LLM, the same model, honest output, because the input is a compressed
representation of the whole group instead of a sample of it. The prompt stops having to say
"you saw only 8 of 320".

## How does this compound?

The scheduler and the drift measure make the thematic layer **self-maintaining**: notes attach on
commit, drift is measured, the rebuild fires when it is actually worth it. Hierarchical summarisation
compounds with that — because the expensive build happens on a schedule rather than never, its cost is
amortised, which is precisely what made the 354-call design affordable to consider at all.

The `404` fix compounds negatively if skipped: every future client integration pays the same tax.

## Scope decided with the operator

| Item | Decision |
|---|---|
| Hierarchical summarisation | **Code behind a flag, default off.** No 2–4 h build in this sprint. |
| Memory distillation | **Enable the launchd job and measure** what the proposals look like. |
| Local reranker | **Skipped.** New feature, not a defect; hybrid search is 55 ms warm and nobody has complained about ordering. |
| `work` vault (2 notes) | **Untouched.** Removing a vault is the operator's call, not a defect. |
| Ниво 3 | **Out of scope** (unchanged; `architecture-graphrag.md` §14). |

## In scope

**Engine** — `404` for unknown `/api` paths · `GraphScheduler` (interval + threshold) · drift measure
on `graph/status` · hierarchical summarisation behind `graph.hierarchicalSummaries`.

**App** — a test target with tests pinning the DTO compatibility semantics (`nil` ≠ `0`) · a level
selector and a `minSize` filter in the Теми pane.

**Ops** — `backupOnChange: true` · enable + measure the distiller · fix `Scripts/release.sh` publish
ordering · measure cold start by phase (measurement only; no fix without the breakdown).

## Not in scope

A cold-start *fix*. The report is explicit that without a per-phase breakdown any optimisation is
guesswork, so this sprint produces the measurement and stops there.
