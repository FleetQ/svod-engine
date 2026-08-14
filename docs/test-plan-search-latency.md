# Test plan — search latency (perf/search-latency)

## Acceptance criteria

1. A repeated `embedQuery` for the same text calls the delegate **once**.
2. `embedPassages` is **never** cached (every call reaches the delegate).
3. Mutating a returned query vector does **not** corrupt a later cache hit.
4. The LRU is bounded — beyond capacity the least-recently-used entry is evicted.
5. `pathsMissingVectors()` returns exactly the paths whose chunks carry no vector, and
   an empty list once everything is embedded.
6. Existing incremental/background/self-heal behaviour is unchanged (regression).
7. `keep_alive` is present in the Ollama embed request body.

## Cases

### `CachingEmbedderTest` (new, hermetic — `FakeEmbedder` counts calls)

| # | Case | Expectation |
|---|---|---|
| C1 | same query twice | `queryCalls == 1`, both results equal |
| C2 | two distinct queries | `queryCalls == 2` |
| C3 | `embedPassages` twice with same text | `passageCalls == 2` (no caching) |
| C4 | mutate returned array, re-query | second result is unmutated (defensive copy) |
| C5 | capacity+1 distinct queries, re-ask the oldest | delegate called again (evicted) |
| C6 | delegation | `model`, `dim`, `knownDim()`, `isActive` match the delegate |
| C7 | delegate throws | exception propagates, nothing poisoned in the cache |

### `pathsMissingVectors` (extend index tests)

| # | Case | Expectation |
|---|---|---|
| P1 | index with `NoneEmbedder` (no vectors) | every seeded path returned |
| P2 | fully embedded index | empty list |
| P3 | partially embedded | exactly the un-embedded paths |
| P4 | empty index | empty list (existing `numDocs == 0` guard) |

P1–P3 assert the `FieldExistsQuery` rewrite is behaviourally identical to the old
`vecBytes`-null scan.

### `OllamaEmbedder` (unit, no network)

| # | Case | Expectation |
|---|---|---|
| O1 | serialized request body | contains `keep_alive` with the configured value |

### Regression (existing suite, must stay green)

`BackgroundIndexTest`, `IndexIncrementalTest`, `IndexSelfHealTest`, `IndexModelChangeTest`,
`IndexHybridTest`, `IndexConcurrencyTest`, `RecallExclusionTest`, `MemoryIndexTest`,
`EmbedderProviderTest`, `RerankTest`, `VersionConsistencyTest`.

`IndexIncrementalTest` and `BackgroundIndexTest` are the load-bearing ones: they exercise
resume-after-interrupt, which is driven entirely by `pathsMissingVectors()`.

## Verification beyond unit tests

- Full suite: `./gradlew test` — read the **counts**, not "BUILD SUCCESSFUL".
  (A Kotlin `@Test` returning non-`Unit` is silently never collected — see the
  `kotlin-junit-silent-skip` memory.)
- Live re-measure after deploy: warm semantic latency and first-search-after-idle,
  against the same query used for the baseline in the research report.

## Known non-goals

No test asserts a latency *number* — timings are machine- and load-dependent and would be
flaky in CI. Latency is verified manually against the live engine and recorded in the PR.
