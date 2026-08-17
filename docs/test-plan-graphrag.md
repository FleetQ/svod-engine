# Test plan — Graph-aware recall (Ниво 1 + Ниво 2)

Companion to `architecture-graphrag.md`. Every item here is a JUnit test in
`engine/src/test/kotlin/dev/svod/engine/graphrag/` unless marked **[manual]**.

> **JUnit gotcha (mandatory, see `mem:kotlin-junit-silent-skip`):** a `@Test` returning a non-`Unit`
> value is silently never collected. Every test body ends in a statement, not an expression, and the
> suite's collected-count is asserted to rise. "BUILD SUCCESSFUL" is not evidence a test ran.

---

## A. Safety invariants — these are the ones that matter

| # | Test | Asserts |
|---|---|---|
| A1 | `graphDisabledByDefault` | Fresh `SvodConfig()` ⇒ `graph.enabled == false`, `summaryProvider == "none"`. |
| A2 | `searchIdenticalWithGraphOff` | Same corpus, same queries, graph off vs graph absent ⇒ **identical** hit ids + order. |
| A3 | `searchIdenticalWithGraphOn` | Graph built and enabled ⇒ `search()` results **unchanged** (graph never touches `search`). |
| A4 | `luceneIndexUntouchedByGraphBuild` | Index dir mtime + `numDocs()` unchanged across a full graph build. |
| A5 | `graphBuildFailureLeavesSearchWorking` | Builder throws ⇒ status `ERROR`, `search()` and `context_pack` still succeed. |
| A6 | `contextPackUnchangedWhenExpandFalse` | `graphExpand=false` ⇒ byte-identical payload to pre-change behaviour. |
| A7 | `corruptSidecarTreatedAsNeverBuilt` | Truncated `communities.json` ⇒ load returns "not built", no throw. |
| A8 | `vaultNeverWritten` | Full build + all queries ⇒ `git status` clean, vault mtimes unchanged. |

## B. Note vectors and edges

| # | Test | Asserts |
|---|---|---|
| B1 | `meanPoolNormalises` | Note vector = L2-normalised mean of its chunk vectors; hand-computed fixture. |
| B2 | `noteWithoutVectorsGetsLinkEdgesOnly` | Unembedded note ⇒ no `SIM` edges, `LINK` edges intact, coverage < 1.0. |
| B3 | `simThresholdRespected` | No `SIM` edge below `simThreshold`; exactly `simEdgesPerNote` kept above it. |
| B4 | `linkEdgesMatchLinkGraph` | `LINK` edge set == `LinkGraph.edges()` symmetrised; unresolved links excluded. |
| B5 | `edgeBuildIsDeterministic` | Two builds over the same corpus ⇒ identical edge list, identical order. |
| B6 | `chunkVectorsNotRetained` | Peak retained note-vector count == note count (guards the 316 MB regression). |

## C. Community detection

| # | Test | Asserts |
|---|---|---|
| C1 | `louvainDeterministic` | Same graph ⇒ identical community assignment across 5 runs. |
| C2 | `louvainSeparatesDisjointComponents` | Two disconnected cliques ⇒ never merged into one community. |
| C3 | `hierarchyContracts` | Level *n+1* community count < level *n*; every level partitions all nodes. |
| C4 | `smallCommunitiesNotSummarised` | Community below `minCommunitySize` ⇒ zero LLM calls for it. |
| C5 | `singletonNodesTolerated` | An isolated note lands in its own community without throwing. |

## D. Summaries / LLM boundary

| # | Test | Asserts |
|---|---|---|
| D1 | `noLlmCallsAtQueryTime` | Spy `SummaryLlm`; run every query surface ⇒ **call count 0**. This is the invariant. |
| D2 | `summaryInputComesFromStrippedChunks` | Note containing `<private>secret</private>` ⇒ the string never appears in the prompt. |
| D3 | `nullSummaryFallsBackToLabel` | `summarise` returns `null` ⇒ community stored with fallback title, build succeeds. |
| D4 | `summaryInputCharCapped` | Oversized community ⇒ prompt length ≤ `summaryInputChars`. |
| D5 | `llmThrowIsContained` | `summarise` throws ⇒ treated as `null`, build still completes. |
| D6 | `summaryCallCountBounded` | Calls == number of communities ≥ `minCommunitySize`, not per note or per chunk. |

## E. Ниво 1 — recall expansion

| # | Test | Asserts |
|---|---|---|
| E1 | `expandAddsNeighboursWithinBudget` | Expanded blocks appear, `estimatedTokens ≤ tokenBudget`. |
| E2 | `expandedBlocksMarked` | Every expanded block has `viaGraph=true` + a `viaPath` naming a primary hit. |
| E3 | `expandNeverDropsPrimaryHits` | Primary block set with expand on ⊇ block set with expand off. |
| E4 | `expandDedupsAgainstPrimaries` | A neighbour already packed is not duplicated. |
| E5 | `expandFailureLeavesPrimaries` | `LinkGraph` throws ⇒ original blocks returned unchanged. |
| ~~E6~~ | ~~`backlinkTieBreakOnlyBreaksTies`~~ | **Dropped with feature 9b** — see `architecture-graphrag.md` §9b. |
| ~~E7~~ | ~~`nullGraphSignalIsIdentity`~~ | **Dropped with feature 9b.** Superseded by A3, which is stronger. |

## F. Query surface (Ниво 2)

| # | Test | Asserts |
|---|---|---|
| F1 | `communitiesRankedByQuery` | Query near community A's centroid ⇒ A ranks first. |
| F2 | `communitiesDeterministicWithoutQuery` | No query ⇒ stable order by size then id. |
| F3 | `levelSelection` | `level` selects the right hierarchy level; out-of-range clamps, no throw. |
| F4 | `staleFlagSurfaced` | HEAD advanced after build ⇒ `stale=true`, results still returned. |
| F5 | `notBuiltReturnsEmptyNotError` | Never built ⇒ empty list + `state=NOT_BUILT`, HTTP 200. |

## G. Contract / compatibility

| # | Test | Asserts |
|---|---|---|
| G1 | `contractVersionBumped` | `CURRENT_CONTRACT_VERSION == "0.24.0"`; `VersionConsistencyTest` still green. |
| G2 | `newRoutesAdditive` | Every pre-existing route responds exactly as before. |
| G3 | `graphRoutes404WhenDisabled` | Graph off ⇒ documented status, never a 500. |
| G4 | **[manual]** old app vs new engine | Installed app **v0.2.15** against the new engine: connect, search, edit, history all work. |
| G5 | **[manual]** new app vs old engine | New app build against engine **v1.14.1**: Теми pane hidden, no errors. |

### G5 found a real defect — feature-detect on the version, never on a 404

Probing the live 0.23.0 engine showed that **an unknown path does not 404**. The engine serves the web
viewer as an SPA fallback, so `/api/v1/graph/status` on a pre-0.24.0 engine returns:

```
200 text/html; charset=UTF-8   <!DOCTYPE html> <html lang="en"> …
```

The first implementation of `GraphModel.loadCommunities()` gated support on catching
`SvodClientError.notFound`. That branch can **never** fire here — the client raises a *decoding*
error instead — so the Теми pane would have rendered visible-but-empty against every older engine,
which is exactly the regression G5 exists to prevent.

Fixed by using the app's existing house pattern: `EngineModel.supportsGraphCommunities`
(`apiVersionAtLeast(0, 24)`), the same mechanism `supportsMemory` and every other optional feature
already use. **Rule for any future optional endpoint in this app: gate on `apiVersion`, never on an
HTTP status.**

## H. macOS app

| # | Test | Asserts |
|---|---|---|
| H1 | `Svod` target builds clean | `xcodebuild … build` — zero warnings from touched files. |
| H2 | **[manual]** communities render | Теми pane lists communities, selection scopes the graph. |
| H3 | **[manual]** feature-detect | `/graph/status` 404 ⇒ pane hidden, no error banner. |
| H4 | `MockSvodClient` fixtures | Previews + offline build compile and render with graph fixtures. |

## I. Performance guardrails — MEASURED

Measured 2026-08-17 against a **copy** of the live `personal` vault (3,096 indexed notes / 79,215
chunks / 681 MB Lucene index), engine on port 7998, `summaryProvider: none`. The live vault and the
live engine on :7619 were untouched throughout (verified: `git status` clean, engine PID unchanged).

| # | Check | Budget | Measured |
|---|---|---|---|
| I1 | Warm `search()` | No regression | **6–14 ms** warm, HTTP 200, 10 hits, graph READY ✅ — see the correction below |
| I2 | Full graph build | Must not block startup | **12–14 s** on a MIN_PRIORITY background thread ✅ |
| I3 | Engine cold start | Unchanged | `/ready` in **0.1 s**; build is never on the boot path ✅ |
| I4 | `communities(query)` | < 50 ms warm | **4–6 ms** warm (first call 2.4 s = Ollama model load, the same cost search pays) ✅ |
| I5 | Sidecar size | < 5% of the index | **12.2 MB = 1.79%** of 681 MB ✅ |

### CORRECTION — the first I1 number was measured against the wrong thing

The originally recorded "0–7 ms" was **wrong**. Those requests used `?query=`, but the App API
parameter is **`q`**, so every one of them returned `400 {"error":"bad_request","message":"provide q
or a filter (e.g. tags)"}`. The timing was real; the subject was not. It measured how fast the engine
rejects a malformed request, and it happened to point in the direction that flattered the conclusion.

Re-measured with `q=`, all HTTP 200 with 10 hits each:

| | median | max |
|---|---|---|
| Live engine on `main` (feature absent) | 73 ms | 166 ms |
| New engine, contract 0.24.0, graph READY | 7 ms | 14 ms |

**Do not read that as "the graph made search faster."** The two engines are not a clean A/B — the
query-embedding cache and background activity differ between them. The claim this table supports is
only that search on the new engine is healthy and not slower.

The authoritative no-regression evidence is **test A3**, which asserts identical hit ids *and* order
with the graph off versus built, on the same corpus in the same process. A benchmark across two
engines cannot establish that; the test can.

**Rule: check an endpoint returns 200 before timing it.** A latency number from an error response is
worse than no number.

### What the build produced at real scale

| Metric | Value |
|---|---|
| Notes in graph | 3,096 |
| Vector coverage | **1.0** (100%) |
| Edges | **14,315** — 732 `LINK` + 13,583 `SIM` |
| Communities | 643 over 3 levels |
| Duplicate labels | **0** across all levels (after two fixes, below) |

**This validates decision D1.** Wikilinks alone gave 732 edges over 19% of notes; adding embedding
similarity produced 14,315 edges over 100% — for zero LLM calls and zero embedder calls, since the
vectors already existed in Lucene.

### Defects the live run caught that the unit tests did not

1. **Useless fallback labels.** The first `fallbackTitle` returned the first member's basename when
   members shared no parent — producing `SKILL`, `AGENTS`, `b42bbb8d6b01` for communities of 200–588
   notes. Fixed to score directory prefixes by depth × coverage. Guarded by
   `a community without a summary is labelled by its deepest shared folder`.
2. **Colliding labels.** The fix above produced four separate communities all labelled `projects`.
   Since the DEFAULT config has no summary provider, that is the default rendering, not an edge case.
   Fixed with a two-pass disambiguation. Guarded by `community labels are unique within a level`.

A third issue was found and is **documented, not fixed**: the coarsest level contains communities of
up to 588 notes, while `summaryInputChars` fits fewer than ten. The prompt now states explicitly that
it is seeing N of M notes (guarded by `a prompt that can only fit part of a community says so`), so
the model cannot silently fabricate a description of 588 notes from 8 — but a genuinely good summary
of a 588-note community still needs hierarchical summarisation, which this sprint does not build.

### Process note

The first validation run reported `vectorCoverage: 0.0116`. That was **not** a code defect: the
validation config named a different embedding model than the index was built with (`bge-m3`), so the
engine correctly started a full re-embed and the graph correctly reported honest coverage over the
1,069 chunks that had vectors at that moment. It is recorded here because the degradation path (B2)
was thereby exercised at full scale, unintentionally, and behaved exactly as designed.

## Acceptance criteria — final result

| # | Criterion | Result |
|---|---|---|
| 1 | Every test in **A** passes (the "did not break Svod" set) | ✅ |
| 2 | **D1**: zero LLM calls at query time | ✅ |
| 3 | Suite green AND the collected count rises by the number added | ✅ **308 tests / 306 passed / 0 failures / 2 skipped**, of which **37 new** — 271 baseline + 37. Counts read from `build/test-results/*.xml`, not from stdout. |
| 4 | No search-latency regression | ✅ (I1: 0–7 ms) |
| 5 | G4 + G5 confirmed by hand | ✅ both. **G5** found a real defect (see above). **G4 run for real**: the installed **v0.2.15** was launched against the 0.24.0 engine via an argument-domain override (`-svod.settings.endpointPort 7998`, not persisted), against a **copy** of the vault. Title bar showed the vault name `personal-g4`, which exists only in that config — proof of which engine it was talking to. Connected (green), tree rendered incl. a note written through the new engine, file opened with its frontmatter table, inspector showed backlinks + commit history. No error banner. The Теми pane was absent, which is correct — v0.2.15 predates it. |
| 6 | Validation against a **copy** of `personal`, never the live vault | ✅ live vault `git status` clean throughout |

### Measurement hygiene learned here

Two runs produced misleading numbers and both were **my counting**, not the tests:

- `grep -c ' FAILED'` on gradle stdout counts `> Task :test FAILED` and `BUILD FAILED`, so a run with
  zero failing tests reported "FAILED: 2".
- A concurrent `./gradlew test` in the same working tree (a review subagent) made a run die on
  `Could not write XML test results`, which looks like a suite failure and is not one. It also
  overwrote `build/test-results/`, producing a report set with none of the new tests in it.

**Rule: take counts from `build/test-results/*.xml` after an exclusive run with that directory
cleared.** Never from a stdout grep, and never while another gradle is live in the same tree.
