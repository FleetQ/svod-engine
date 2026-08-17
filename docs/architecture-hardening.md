# Svod — hardening sprint (architecture)

Companion to `design-hardening.md`. Four engine changes, two app changes, four ops actions.

## E1 — `404` for unknown `/api` routes

**Today.** The web viewer is served as an SPA with a catch-all fallback, so *every* unmatched path
returns `200 text/html`. Measured: `/api/v1/does-not-exist`, `/api/v1/graph/nope`, `/api/v9/settings`
all `200 text/html`.

**Change.** Scope the fallback: a request whose path starts with `/api/` and matched no route returns
`404` with the existing `ErrorDto` shape (`{"error":"unknown_route","message":"..."}`), JSON
content-type. Everything else keeps the SPA fallback exactly as it is — the viewer's client-side
routes must keep working.

**Placement.** Ktor's `StatusPages`/`route` fallback is where the SPA catch-all lives; the guard goes
there, not in individual routes, so no route can forget it.

**Blast radius.** Any client that today receives HTML for a typo will start receiving `404`. That is
the point. `apiVersion`-based feature detection is unaffected — nothing in the app depends on the
fallback returning 200, and the documented rule ("feature-detect on apiVersion, never on 404") stays
correct: it is now correct *and* enforced from both sides.

## E2 — `GraphScheduler`

**Shape.** Mirrors `SourceScheduler` exactly — a `CoroutineScope`, a nullable interval, a suspend
body, a `start()`/`stop()` pair, failures logged and the loop continues. No new concepts.

```kotlin
class GraphScheduler(scope, intervalMinutes: Int?, attachedThreshold: Int?, rebuildAll: suspend () -> Unit)
```

**Trigger rule.** A tick fires a rebuild when EITHER condition holds, so the operator can express
"nightly" or "when it has actually drifted" or both:

- `intervalMinutes` elapsed since the last successful build, **and**
- `attachedThreshold` — number of incrementally attached notes at or above the threshold.

Both null ⇒ the scheduler never starts, which is the default. `GraphService.rebuild()` already
no-ops when a build is running, so a tick during a build is free.

**Why not just "every N hours".** A rebuild is ~15 minutes of local LLM time. Firing it on a vault
that has not moved is pure waste; firing it on a vault with 400 attached notes is overdue. The
threshold is the honest trigger and the interval is the safety net.

**Wiring.** `SvodNode.start()` next to the other three schedulers; `stop()` in the same block.
Per-vault iteration like `SourceScheduler` does.

## E3 — drift measure

**Problem.** `attachedCount` counts notes, not divergence. 50 attached notes that all landed in the
community a full Louvain would have chosen is not drift; 50 that did not, is.

**Measure.** `driftRatio` = attached notes whose *current* dominant-neighbour vote no longer matches
the community they sit in, divided by attached notes. Computed on the attach thread at the end of a
pass (the neighbours and vectors are already in hand — no extra Lucene work), cached like
`pendingCount`, surfaced on `graph/status`.

**Honesty constraint.** It is a proxy, not a Louvain comparison, and must be documented as such: it
detects notes whose neighbourhood has since moved on, not the full structural divergence a rebuild
would resolve. `0.0` does not prove the partition is optimal.

## E4 — hierarchical summarisation (flag, default off)

**Today.** `summarise()` walks the top `summariseTopLevels` levels and builds each prompt from raw
member note excerpts, capped at `summaryInputChars`. A 320-note community is described from ~8 notes,
disclosed by the "видя само N от общо M" footer.

**Change.** With `graph.hierarchicalSummaries: true`:

1. summarise **level 0** from raw excerpts (unchanged prompt path — median 7 members fits entirely);
2. for each coarser level, build the prompt from the **child communities' titles and summaries**
   instead of raw note text. A child is a level-(n−1) community whose members are a subset of this
   community's members;
3. the sample-disclosure footer is emitted only when children still had to be truncated.

**Child resolution.** Louvain levels are nested by construction (each level merges the previous), but
the code must not *assume* it: a child is matched by membership subset, and any level-(n−1) community
not fully contained is skipped and counted. If no children resolve, the level falls back to the
existing raw-excerpt path — degrade, never fail.

**Cost.** 258 + 58 + 38 = 354 calls on `personal` versus 38 today. Off by default precisely because
that is a 2–4 hour build; the flag exists so the operator can run it when the machine is free.

**Not changed.** The summary parsing, the language decision, the privacy guard
(`stripPrivateSpans`), the per-note cap, and the "LLM only at build time" invariant.

## A1 — app test target

First tests in this repo. A unit-test target (`SvodTests`), no UI tests, no host-app dependency
beyond the `Svod` target. Covers the compatibility semantics that are currently only checked by hand:

- `GraphStatus` decodes from an **0.24.0-era payload** (no `incremental`/`attachedCount`/
  `pendingCount`) without throwing, and `newSinceBuild` is `nil`;
- `incremental: false` ⇒ `newSinceBuild` is `nil` even when counts are present;
- `incremental: true` with `0/0` ⇒ `newSinceBuild == 0` (a real answer, not "unknown");
- `GraphCommunity` decodes without `addedSinceSummary`.

That is the exact logic the pane branches on, and the only place where `nil` and `0` mean different
things.

## A2 — level selector + `minSize` in the Теми pane

**Today.** The pane always requests the coarsest level and shows `limit: 50` communities ordered by
size descending — which includes single-member communities once the real themes run out (measured:
level 2 has 38 communities with ≥3 members out of 546).

**Change.** A compact level control in the header (coarsest ⇄ finest, driven by `levelCount` from
status) and a client-side `minSize >= 3` filter so singleton rows never render. Selecting a level
re-fetches; the theme→tree filter behaviour is unchanged.

**Why client-side filtering.** `size` is already in the payload and the listing is capped at 50 rows;
adding a server parameter would be a contract change for something the client can do for free.

## O1–O4 — ops

- **O1** `backupOnChange: true` on the live config (6-hour exposure → per-commit).
- **O2** install + run the distiller once, and report what the proposals look like.
- **O3** `Scripts/release.sh`: commit and push the appcast **before** `gh release create`, so the tag
  lands on the release commit; and do not let a failed publish abort before the appcast is committed.
- **O4** measure cold start by phase (watcher build / Lucene open / git head) — measurement only.

## Contract impact

`GraphStatus` gains `driftRatio` (additive) ⇒ **0.26.0 → 0.27.0**. `graph.rebuildIntervalMinutes`,
`graph.rebuildAfterAttached` and `graph.hierarchicalSummaries` are config, not contract. The `404`
change alters *behaviour* on undefined paths, which the contract never specified — documented in the
changelog as a behaviour change, not a breaking contract change.

## Sequencing

E1 (isolated) → E3 (small, feeds the scheduler's trigger) → E2 (uses E3) → E4 (largest, isolated) →
A1 → A2 → ops. Engine and app ship as two PRs, engine first.
