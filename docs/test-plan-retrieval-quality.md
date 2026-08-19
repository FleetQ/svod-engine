# Test plan — Retrieval Quality

Covers both units. "The harness" is itself code and gets tested like code — an eval that silently
computes the wrong number is worse than no eval.

## Unit 1

### T1. Metric correctness (pure unit tests, no index)

The metric functions are the measuring instrument; they get hand-computed expectations, not
self-consistency checks.

| Case | Input | Expected |
|---|---|---|
| perfect ranking | all relevant first | recall@k = 1.0, nDCG@10 = 1.0, MRR = 1.0 |
| nothing relevant retrieved | no gain > 0 in R | recall = 0, nDCG = 0, MRR = 0 |
| first relevant at rank 3 | — | MRR = 1/3 |
| graded ordering | gains 3 then 1 vs 1 then 3 | nDCG(3,1) > nDCG(1,3) |
| k truncation | relevant only at rank 7 | recall@5 = 0, recall@10 > 0 |
| empty result list | R = [] | all three = 0, no exception |
| empty relevant set | all gains 0 | defined, no divide-by-zero |
| exact nDCG value | 2 docs, gains 3 and 1, reversed order | hand-computed constant, ±1e-9 |

The exact-value case is the one that catches a wrong log base or an off-by-one in the discount —
the two errors that make an nDCG implementation look plausible while being wrong.

### T2. Path deduplication

Two chunks of the same note in the top 3 must count as one path, and must not consume two slots at
k. Asserted directly on the dedup helper with a synthetic hit list.

### T3. Leg P — pipeline regression (CI gate)

- Runs on `FakeEmbedder` + the committed synthetic corpus, unconditionally.
- Asserts recall@5 and nDCG@10 floors for HYBRID and KEYWORD.
- SEMANTIC on `FakeEmbedder` gets a **loose** floor only: a hashed bag-of-words has no semantic
  meaning, so a tight floor there would be a number measuring the wrong subject.
- Must be hermetic: temp vault via `IndexFixture`, no network, no model download.

### T4. Leg S — semantic quality (skips without the cached model)

- `assumeTrue(cachedE5ConfigOrNull() != null)`.
- Same corpus, real e5-small. Floors per mode, and one **cross-mode** assertion that matters:
  HYBRID ≥ max(KEYWORD, SEMANTIC) on nDCG@10 for the corpus as a whole. If fusion doesn't beat its
  own legs, fusion is not earning its complexity.
- Includes Bulgarian queries against Bulgarian notes, and at least two **cross-lingual** pairs
  (BG query → EN note) — that is the specific capability `multilingual-e5-small` was chosen for,
  and nothing currently tests it.

### T5. Leg V — real vault (opt-in, never in CI)

- `assumeTrue` on `svod.eval.vault` + `svod.eval.golden`.
- Read-only against the vault; must not write, index into, or mutate the user's `.svod/`. Index
  into a temp dir. This is asserted by construction (fixture points elsewhere) and verified by
  running it and checking the vault's git status is clean afterwards.
- Prints the metric table; asserts nothing (no floors on private, changing data — a floor there
  would fail for reasons unrelated to code).

### T6. Negative verification (mandatory before the PR)

Per `verify-tests-negatively`: for each new gate, break the thing on purpose and watch the gate
fail.

- Force `Rrf.fuse` to return the keyword leg only → leg P HYBRID floor must fail.
- Shuffle the ranked list before scoring → floors must fail.
- Return an empty result → all floors must fail.

A floor that cannot fail is not a gate. Each of these is run, observed, then reverted.

### T7. CI stays green and fast

- `./gradlew test --no-daemon` on a runner with no cached model: leg S and leg V SKIP, leg P runs.
- Skip count goes 2 → 3 or 4 (the new assumeTrue-guarded legs); pass count rises by the new tests.
  Counts read from `build/test-results/test/*.xml`, never from "BUILD SUCCESSFUL".
- Added wall-clock for leg P: target < 5s.

## Unit 2

### T8. Provider wiring

- `Rerankers.create()` with `provider = LOCAL_ONNX` returns the local reranker; unknown string
  still rejected.
- `"local-onnx"` accepted by `SvodConfig` validation; round-trips through `toRerankerConfig()`.
- **Three-place sync check**: enum value, `RERANKER_PROVIDERS`, contract description — a test
  asserts the enum and the validator list agree, so the pair cannot drift silently.
- `GET /api/v1/settings` reports `provider = "local-onnx"`, `active = true` when configured.

### T9. Reranker behaviour

- Reorders: a passage that is lexically poor but semantically the answer moves up.
- Degrade paths (extend `RerankTest`'s existing shape): model missing → search still returns fused
  order; predict throws → fused order; blank query → untouched.
- Thread safety: N concurrent searches with the reranker active return correct results and do not
  corrupt the predictor. Asserted with a concurrency test in the shape of `IndexConcurrencyTest`.

### T10. The one that decides whether Unit 2 ships

Leg S, same corpus and queries, with and without the reranker:

- **Ship criterion**: nDCG@10 with reranker > without, on both the BG and EN query subsets, and
  measured rerank latency for 50 pairs ≤ 300ms on this machine.
- If quality improves but latency blows the budget → ship default-off with the measured number in
  the docs and a smaller `rerankTopK` recommendation.
- If quality does not improve → **do not ship the model**; keep the provider only if it is
  demonstrably useful with a different one. Record the negative result. An eval that can only ever
  say yes is the failure mode this whole sprint exists to remove.

### T11. Model integrity

- sha256 pin verified on download; a corrupted file fails loudly (mirrors `ModelManager`'s existing
  checksum behaviour).
- Reranker absent from disk and no network → falls back to `NoneReranker`, search unaffected.

## Out of scope

- No `setReranker()` hot-swap, so no test for it.
- No reranking inside `context_pack`.
- No LLM-as-judge labelling.
