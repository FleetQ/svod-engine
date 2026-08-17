# Svod — hardening sprint (test plan)

Companion to `architecture-hardening.md`. Every case names what it would catch; a case that passes
with the change reverted is worthless and is called out where the risk is real.

## E1 — `404` for unknown `/api` routes

| id | case | expectation |
|---|---|---|
| E1.1 | `GET /api/v1/does-not-exist` | `404`, `application/json`, body parses as `ErrorDto` |
| E1.2 | `GET /api/v9/settings` (unknown version) | `404` JSON — a wrong version prefix is still an API path |
| E1.3 | `POST /api/v1/nope` | `404` JSON — not method-dependent |
| E1.4 | `GET /` and `GET /some/spa/route` | still `200 text/html` — the viewer must keep client-side routing |
| E1.5 | `GET /api/v1/settings` | unchanged `200` JSON — the guard must not shadow real routes |
| E1.6 | `GET /metrics` | unchanged (`text/plain`) — not under `/api`, must not be caught |

**Negative direction:** E1.1 must fail with the guard removed. Verified by reverting the guard once.

## E2 — `GraphScheduler`

| id | case | expectation |
|---|---|---|
| E2.1 | both triggers null | `start()` launches no job; no rebuild ever fires |
| E2.2 | `attachedThreshold = 2`, 2 notes attached | a tick fires exactly one rebuild |
| E2.3 | `attachedThreshold = 5`, 2 notes attached | a tick fires **no** rebuild |
| E2.4 | rebuild body throws | the loop survives and the next tick still fires |
| E2.5 | tick while a build is running | no second build (delegates to `rebuild()`'s existing guard) |
| E2.6 | `stop()` | the job is cancelled; no further ticks |

E2.2/E2.3 are the pair that makes the threshold meaningful — either alone would pass with the
condition inverted. E2.1 asserts on the **job** (`isRunning`), not on "nothing happened in 200 ms":
the loop sleeps a full tick before doing anything, so a time-based assertion passes whether or not
the guard exists.

## E3 — drift measure

| id | case | expectation |
|---|---|---|
| E3.1 | no attached notes | `driftRatio == 0.0`, and documented as "nothing attached", not "no drift" |
| E3.2 | attached note still voting for its own community | `driftRatio == 0.0` |
| E3.3 | attached note whose neighbourhood moved to another community | `driftRatio > 0.0` |
| E3.4 | after a full rebuild | `driftRatio` resets to `0.0` with `attachedCount` |
| E3.5 | `graph/status` shape | field present, additive, contract 0.27.0 asserted |
| E3.6 | one drifted note + one note attached by the measuring pass | exactly `0.5` — the denominator must include the current pass |

E3.3 is the load-bearing one; without it the measure could be hard-coded to 0 and everything else
would still pass. **E3.6 was added after review** and caught a real defect: the measure read the
*pre-pass* `attachedPaths`, so it was structurally blind to everything the pass it ran in had just
attached. E3.2 alone could not see that — it returns `0.0` whether the vote runs or the sample is
empty, which is the "metric that cannot see the failure it exists to catch" shape.

## E4 — hierarchical summarisation

| id | case | expectation |
|---|---|---|
| E4.1 | flag off (default) | every prompt uses the raw-excerpt delimiter, **none** uses the child-composition one, and only the coarsest level is summarised — the two properties hierarchical mode breaks |
| E4.2 | flag on, level 0 | prompt still built from raw note excerpts |
| E4.3 | flag on, coarser level | prompt contains child **titles/summaries**, and contains no raw note body text |
| E4.4 | flag on, children unresolvable | falls back to the raw-excerpt path; build still succeeds |
| E4.5 | flag on, all children fit | no "видя само N от общо M" footer — the disclosure must not lie in the other direction |
| E4.6 | privacy | `<private>` content never reaches a prompt on either path (existing D2 guard re-run with the flag on) |
| E4.7 | call count | one call per eligible community per summarised level; bounded by community count, never chunk count |
| E4.8 | language decision | unchanged — Cyrillic children still pin Bulgarian |

E4.1 is the compatibility guard and E4.3 is the feature; E4.5 is the honesty guard, because an
unconditional footer would be a fabrication in the opposite direction.

## A1 — app DTO compatibility (new test target)

| id | case | expectation |
|---|---|---|
| A1.1 | decode an 0.24.0-era `GraphStatus` JSON (no new fields) | succeeds; `newSinceBuild == nil` |
| A1.2 | `incremental: false`, counts present | `newSinceBuild == nil` — off ≠ "nothing changed" |
| A1.3 | `incremental: true`, `attached 0`, `pending 0` | `newSinceBuild == 0` — a real answer |
| A1.4 | `incremental: true`, `attached 2`, `pending 1` | `newSinceBuild == 3`, `pendingCount == 1` |
| A1.5 | `GraphCommunity` without `addedSinceSummary` | decodes; field is `nil` |

A1.1 is the one that would have caught a non-optional field added carelessly — the failure mode is a
client that cannot talk to an older engine at all.

## A2 — pane

Manual, in the running app (no UI test target): the level control shows `levelCount` options,
switching level re-fetches and re-renders, singleton communities never appear, and selecting a theme
still filters the notes tree.

## Regression gates (must not move)

- full engine suite green; **counts read from `build/test-results/*.xml` after an exclusive run**, not
  from stdout;
- `search()` results identical before/after (existing A2/A3 tests);
- no model call on any query path (existing D1);
- Lucene index and vault untouched (existing A4/A8);
- `VersionConsistencyTest` green — contract bumped in **both** `openapi.yaml` and `ApiCompatibility`.

## Live acceptance (after deploy)

1. `curl /api/v1/nope` → `404` JSON; `curl /` → `200 html`.
2. `graph/status` carries `driftRatio`; `apiVersion` reports `0.27.0`.
3. Scheduler configured off by default in the shipped defaults; enabled in the live config and
   observed not to fire while nothing has drifted.
4. Distiller run once; proposal count and a quality read reported.
5. Backup `backupOnChange: true` observed in `/sync/config`.
