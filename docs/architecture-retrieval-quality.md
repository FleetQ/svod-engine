# Architecture — Retrieval Quality

Companion to `design-retrieval-quality.md`. Two units, shipped in order.

## Ground truth established by recon (file:line, verified)

| Fact | Where |
|---|---|
| `Reranker` = `{ model, provider, isActive, rerank(query, docs): List<Float> }` | `index/Reranker.kt` |
| A **remote** TEI/Jina/Cohere reranker already exists | `index/RemoteReranker.kt:25` |
| Provider enum is `{ NONE, REMOTE }`; single resolution point | `index/Rerankers.kt:6,23-32` |
| Rerank runs strictly AFTER RRF fusion, over top `rerankTopK` (50) | `index/IndexService.kt:524,574-590` |
| Rerank failure already degrades to fused order | `index/IndexService.kt:581,587` |
| Reranker is boot-time fixed — no `setReranker()` (unlike `setEmbedder` at `:359`) | `index/IndexService.kt:70` |
| Provider string validated against a list; mapped in `toRerankerConfig()` | `lifecycle/SvodConfig.kt:437,357-371` |
| DJL predictors are **not thread-safe**; embedder serializes on a lock | `index/OnnxLocalEmbedder.kt:20-21` |
| Contract 0.27.0; `RerankerInfo.provider` is a bare string, not an enum | `contract/openapi.yaml:4,1560-1566` |
| Search returns chunk-level hits: `SearchHit(chunkId, path, heading, …)` | `index/SearchModels.kt:36-47` |
| `FakeEmbedder` is a hashed bag-of-words, dim 64 — deterministic, **not semantic** | `test/…/IndexTestSupport.kt:20-55` |
| Opt-in test gating pattern: `assumeTrue(...)`, system properties forwarded in `tasks.test` | `OnnxEmbedderTest.kt:24-30`, `build.gradle.kts:163-169` |

Correction to the initial framing: the reranker stage is not entirely unimplemented — a remote one
exists. What is missing is the **local, in-process** provider and any way to tell whether either
helps.

---

## Unit 1 — Eval harness

### A1. Metrics live in test scope, not production

New file `engine/src/test/kotlin/dev/svod/engine/index/RetrievalEval.kt`, sibling to
`IndexTestSupport.kt`. Zero production surface: no new endpoint, no CLI, no gradle task. Evaluating
retrieval is a development concern, and the codebase has no task/CLI infra for tests — a plain
JUnit class is the zero-infra path (`IndexHybridTest` sets the precedent).

### A2. Path-level granularity

`search()` returns chunk hits; several chunks can come from one note. Golden labels are per **note
path** ("did search surface the right note"), so the runner deduplicates hits by `path`, keeping
first occurrence, before scoring. Chunk-level scoring would punish a correct note for having its
best chunk second.

### A3. Metric definitions (pinned, so numbers are comparable across sessions)

For a ranked, path-deduplicated list `R` and graded gains `g(path) ∈ {0,1,2,3}`:

- `recall@k` = |{p ∈ R[0:k] : g(p) > 0}| / |{p : g(p) > 0}|
- `DCG@k` = Σᵢ (2^g(Rᵢ) − 1) / log₂(i + 2), i from 0; `nDCG@k` = DCG@k / IDCG@k (ideal ordering)
- `MRR` = 1 / (1 + index of first p with g(p) > 0), 0 when none

Exponential gain is the standard IR form and is what makes a grade-3 hit worth materially more than
a grade-1 — the whole point of grading rather than binary labels.

### A4. Three legs, three purposes

| Leg | Corpus | Embedder | Runs in CI | Grades |
|---|---|---|---|---|
| **P — pipeline** | synthetic, committed | `FakeEmbedder` | always | BM25 + RRF + filters wiring; deterministic |
| **S — semantic** | synthetic, committed | real e5-small | skipped unless model cached | actual embedding quality, BG + EN |
| **V — vault** | real `personal` (3384 notes) | real e5-small | never | true numbers for tuning |

Leg P is the regression gate: hermetic, fast, no model. Leg S is where the reranker will be graded
in Unit 2 — `FakeEmbedder` is a hash, so it cannot express semantic similarity and a reranker
measured against it would be measuring noise. Leg V is the honest number, and it never enters git.

### A5. Gating, following the existing patterns exactly

- Leg S: `assumeTrue(cachedE5ConfigOrNull() != null, …)` — the `OnnxEmbedderTest.kt:24-30` pattern.
  Skips on a clean CI runner, exactly like the two tests already skipping today.
- Leg V: `assumeTrue(System.getProperty("svod.eval.vault") != null, …)`, with the golden set at
  `svod.eval.golden`. Both forwarded in `tasks.test` using the conditional
  `System.getProperty(p)?.let { … }` idiom already used for `svod.perf`/`svod.notes`/`svod.writers`
  (`build.gradle.kts:168-169`) — so absent properties change nothing.

### A6. Golden-set file format (leg V only)

JSONL, one query per line, never committed:

```json
{"q": "как се деплойва engine-ът", "gains": {"ops/deploy.md": 3, "ops/launchd.md": 1}}
```

Leg P/S golden sets are Kotlin literals in the test sources (the `IndexHybridTest.seedCorpus()`
precedent) — no parser needed for them, and no fixture files to drift.

### A7. Assertions are floors, not equalities

Each leg asserts `recall@5 >= FLOOR` and `nDCG@10 >= FLOOR` per mode, with floors set from the
first measured run minus a small margin. A floor catches regressions without failing on every
harmless reordering. The run always prints the full metric table, so a passing run still reports
the numbers.

---

## First measurements — what the harness found immediately

Measured 2026-08-19, `multilingual-e5-small`, 27-note bilingual corpus, 37 queries.

```
S/HYBRID                      n=37  R@1=0.622 R@5=0.811 R@10=0.811 nDCG@10=0.786
S/KEYWORD                     n=37  R@1=0.514 R@5=0.730 R@10=0.770 nDCG@10=0.694
S/SEMANTIC                    n=37  R@1=0.595 R@5=0.824 R@10=0.878 nDCG@10=0.808
S/HYBRID bg                   n=14  R@1=0.821 R@5=1.000 R@10=1.000 nDCG@10=0.998
S/HYBRID en                   n=13  R@1=0.808 R@5=1.000 R@10=1.000 nDCG@10=0.998
S/HYBRID cross-ling hard      n=6   R@1=0.000 R@5=0.167 R@10=0.167 nDCG@10=0.083
S/HYBRID cross-ling control   n=4   R@1=0.250 R@5=0.500 R@10=0.500 nDCG@10=0.408
S/SEMANTIC cross-ling control n=4   R@1=0.250 R@5=0.750 R@10=0.750 nDCG@10=0.533
```

### F1 — Cross-lingual retrieval is roughly half-working

Same-language retrieval is effectively perfect (recall@5 = 1.000, nDCG 0.998 for both BG→BG and
EN→EN). Cross-lingual collapses to recall@5 = 0.167.

The first reading — "those queries were just hard" — was a confound: every cross-lingual query was
*also* a paraphrase and *also* carried a distractor. So four **control** queries were added, which
are literal translations of wording the note itself uses, two of them with zero shared tokens. The
controls still only reach recall@5 = 0.500 (HYBRID) / 0.750 (SEMANTIC), against 1.000 for
same-language. The gap survives the isolation, so it is a real capability gap, not query difficulty.

This is the single capability the multilingual embedder was chosen for, and nothing in the suite
measured it until now. Cause is not yet established — candidates: e5-small is simply weak at
BG↔EN alignment (it is the *small* variant), the `query:`/`passage:` prefixes interact badly across
languages, or Lucene's analyzer choice hurts the lexical leg for Cyrillic. **Hypothesis, not
conclusion** — the next step is comparing e5-small against e5-base on the same golden set.

### F2 — RRF fusion loses to its own best leg

HYBRID (0.786) sits *below* SEMANTIC (0.808) overall, and far below it on the cross-lingual controls
(0.408 vs 0.533). The default search mode is worse than one of its own inputs.

**Measured** (not in dispute): HYBRID nDCG@10 < SEMANTIC nDCG@10, on the full set and on every
cross-lingual subset. The same shape appears in leg P, where the deliberately meaningless
`FakeEmbedder` leg drags HYBRID (0.557) below KEYWORD (0.694) — which is also why leg P carries only
a loose semantic floor.

**Mechanism** — supported by the code's arithmetic, not yet traced through a specific query, so
call it a well-founded hypothesis rather than a proven cause. `Rrf.fuse` (`index/Rrf.kt`) adds
`1/(60 + rank + 1)` per list with **no per-leg weight**, and `search()` passes both legs as equals
(`IndexService.kt:521`) with `cand = maxOf(limit * 5, 50)`. So a note ranked #1 by semantic but
absent from the keyword leg's 50 candidates scores `1/61 = 0.0164`, while a note ranked #3 by
*both* legs scores `2 × 1/63 = 0.0317` and outranks it. Agreement between legs beats strength in
one leg, by construction — which is exactly the wrong trade when one leg is much better than the
other on a given query.

Confirming it properly means tracing the queries where HYBRID lost rank against the two leg
orderings. That is a follow-up, not a claim being made here.

Not fixed here: Unit 1 builds the scoreboard, it does not tune the ranker. Recorded as the first
piece of work the scoreboard justifies — leg weighting, or score-aware fusion, measured against
these exact numbers.

### Why the gates are floors, not equalities

`HYBRID_MAY_TRAIL_BEST_LEG_BY = 0.03` pins F2's *current* size rather than asserting it away: the
suite stays green on the known defect and goes red if fusion degrades further. Same for
`S_CROSSLINGUAL_RECALL5 = 0.27` — it locks in a known-bad number so it can only be moved upward.
Writing a bad number down is what stops it from being rediscovered in six months.

### Negative verification (run, observed, reverted)

| Injected defect | Result |
|---|---|
| `rankedPaths` returns `emptyList()` | legs P and S both FAIL |
| `rankedPaths` returns the ranking reversed | legs P and S both FAIL |
| `Rrf.fuse` ignores all but the first list | leg S FAILS (`HYBRID recall@5 0.730 below floor 0.78`) |

Each gate was demonstrated to fail before being trusted.

---

## Unit 2 — Local ONNX cross-encoder reranker

Gated on Unit 1: the reranker ships only if leg S shows it beats plain RRF.

### A8. Where the code goes

| Change | File |
|---|---|
| `LOCAL_ONNX` enum value | `index/Rerankers.kt:6` |
| `when` branch constructing it | `index/Rerankers.kt:23-32` |
| `"local-onnx"` accepted provider string | `lifecycle/SvodConfig.kt:437` |
| string → enum mapping | `lifecycle/SvodConfig.kt:357-371` |
| new `OnnxLocalReranker` | `index/OnnxLocalReranker.kt` (new) |
| pinned model + sha256 | `index/ModelManager.kt` |
| provider description + version bump | `contract/openapi.yaml:4,1560-1566` |

The literal `"local-onnx"` must land in **three** places (enum, validator list, contract
description) — recon flags this as the easiest thing to half-do.

### A8b. Model choice — `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1`

Selected from the verified shortlist. 117.6M params, XLM-RoBERTa backbone distilled from XLM-R-Large,
max seq ~512, **Apache-2.0**, first-party ONNX.

Rejected, with reasons that matter:

| Candidate | Why not |
|---|---|
| `jinaai/jina-reranker-v2-base-multilingual` | **cc-by-nc-4.0** — non-commercial. Disqualifying for a shipped product. |
| `mixedbread-ai/mxbai-rerank-xsmall-v1` | Model card declares `language: en`. Smallest, but monolingual — wrong subject entirely. |
| `BAAI/bge-reranker-v2-m3` | 568M and **no first-party ONNX**; community mirror only, in split external-data format. Cannot hit the latency budget. |
| `BAAI/bge-reranker-base` | 278M, no quantized ONNX published (fp32 is 1.1GB); en/zh-centric tuning. |
| `Alibaba-NLP/gte-multilingual-reranker-base` | No first-party ONNX; ~306M; licence unverified. |

**Two open risks, both to be closed by measurement before this ships:**

1. **Bulgarian is not in the mMARCO fine-tune language set** (14 langs, bg not among them). Coverage
   would come from the XLM-R backbone via cross-lingual transfer — the same mechanism e5 relies on,
   but unverified for bg on this model. Leg S's Bulgarian and cross-lingual subsets exist precisely
   to answer this, and a bad answer means this model does not ship.
2. **Latency is an estimate, not a measurement.** No published Apple-Silicon benchmark exists for
   this model; ~150-400ms for 50 pairs is an extrapolation. It gets benchmarked on the real box.

**Artefact landmine:** the quantized file is `onnx/model_qint8_arm64.onnx` — **architecture-specific**.
It will not load on x86 (CI runners, Linux users). The pin therefore needs either per-arch selection
or the portable fp32 `onnx/model.onnx` (471MB) as the default with arm64-int8 as an opt-in. Since the
reranker is default-off and DJL is JVM-only anyway, the simplest correct choice is: pin the fp32
artefact, and treat arm64-int8 as a later optimisation once the quality question is settled.

### A9. Cross-encoder ≠ embedder

`TextEmbeddingTranslatorFactory` (`OnnxLocalEmbedder.kt:54-65`) maps one text → vector. A
cross-encoder maps (query, passage) → one logit.

No hand-written `Translator` is needed — but not for the reason the model research gave. That
report suggested `TextEmbeddingTranslatorFactory` + `optArgument("reranking", true)`, from a DJL
discussion thread. **Verified against the DJL 0.30.0 jars actually on the classpath** (`javap` over
`tokenizers-0.30.0.jar` and `api-0.30.0.jar`), the real API is better:

```
ai.djl.huggingface.translator.CrossEncoderTranslator
    implements Translator<ai.djl.util.StringPair, float[]>
  static Builder builder(HuggingFaceTokenizer)
  Builder: optSigmoid(boolean), optIncludeTokenTypes(boolean), optBatchifier(Batchifier)
  NDList batchProcessInput(ctx, List<StringPair>)
  List<float[]> batchProcessOutput(ctx, NDList)
```

Three things this changes:

1. There is a **purpose-built cross-encoder translator**; the embedding factory is the wrong tool.
   There is **no `CrossEncoderTranslatorFactory`** in 0.30.0 (checked — only TextEmbedding,
   TextClassification, TokenClassification, FillMask, QuestionAnswering factories exist), so the
   translator is constructed explicitly and passed via `Criteria.optTranslator(...)`, not
   `optTranslatorFactory(...)`.
2. `batchProcessInput`/`batchProcessOutput` exist, so A10's "one batched predict for ≤50 pairs" is
   supported natively rather than needing a hand-rolled loop.
3. `optIncludeTokenTypes` is a landmine: XLM-RoBERTa models (which
   `mmarco-mMiniLMv2-L12-H384-v1` is) have **no `token_type_ids` input**, while BERT-family models
   do. Passing token types to a graph that lacks that input fails at inference. Verify against the
   exported ONNX graph's inputs before wiring.

`ModelManager.BUNDLED` is `id → dim`, which is embedder-shaped and does not describe a reranker;
reranker pins get their own map rather than overloading that one. Whether `resolve()`'s
`model.onnx` + `tokenizer.json` layout fits is **verified before coding**, not assumed.

### A10. One batched predict under one lock

All ≤50 pairs go through a single `Predictor.batchPredict` call inside the same `synchronized(lock)`
shape the embedder uses. Per-pair calls would take the lock 50 times per query and serialize
concurrent searches badly.

### A11. Latency is a gate, not a hope

The eval run reports rerank latency alongside quality. Model choice: small multilingual
(~100-150M), target ≤300ms for 50 pairs on M-series CPU. If measured latency blows the budget, the
fallback is a smaller `rerankTopK`, then a smaller model — not shipping a slow default.

### A9b. The DJL cross-encoder translator does not work — measured, not assumed

`CrossEncoderTranslator` is the obvious choice and it fails at inference on DJL 0.30.0:

```
ai.djl.translate.TranslateException: UnsupportedOperationException: Data type not supported: uint32
  at ai.djl.onnxruntime.engine.OrtUtils.toTensor(OrtUtils.java:67)
```

Its tensors reach ONNX Runtime as **uint32**, which `OrtUtils` rejects. So the reranker builds its
inputs itself (`PairScoringTranslator` in `OnnxLocalReranker.kt`): explicit **int64** `input_ids` +
`attention_mask`, named (ONNX binds by name, not position), padded by hand to the longest member of
the batch with the model's own pad id (1, from its `config.json`).

Returning `null` from `getBatchifier()` hands the whole list to `batchProcessInput`, so a batch pads
to its longest member rather than to `MAX_SEQ_LEN` — several times less compute per query for
typical chunk sizes.

The graph's inputs were read off the artefact itself (`input_ids`, `attention_mask`, **no**
`token_type_ids`). Copying the embedder's `includeTokenTypes = true` would have failed — the e5
export needs it, this one has no such input.

### A12. Default off (D3)

`NoneReranker` stays the default. No hot-swap path is added: the reranker is resolved at vault-open
(`lifecycle/VaultContext.kt:81-88`) and that stays true. Adding `setReranker()` + an HTTP control
endpoint is deliberately out of scope — it is a separate feature with its own concurrency questions.

**This decision is now the weakest part of the design and should be revisited by a human.** D3 said
"default off, revisit once the golden set shows the win". The win is far larger than expected (see
below). What still argues for off is cost, not quality: a 471 MB first-run download and ~262 ms
added to every search. That trade belongs to the product owner, not to this document.

---

## Unit 2 results — the reranker earns its place, and closes F1

Measured 2026-08-19, same corpus and golden set, `mmarco-mMiniLMv2-L12-H384-v1` over the top 50
fused candidates.

| | plain fusion | reranked | delta |
|---|---|---|---|
| nDCG@10, all 37 queries | 0.786 | **0.904** | +0.118 |
| recall@1, all 37 | 0.622 | **0.743** | +0.121 |
| nDCG@10, cross-lingual | 0.213 | **0.900** | **+0.687** |
| recall@1, cross-lingual | 0.100 | **0.850** | +0.750 |
| 50 pairs, M-series CPU | — | **262 ms** | budget 300 ms |

The ship criterion (`reranked nDCG@10 > plain`) was written into the test before the numbers were
known, so it could have said no. It said yes, decisively.

**The cross-lingual result looked like the headline — and it was wrong.** On the synthetic corpus,
reranked cross-lingual recall@1 went 0.100 → 0.850, which read as "F1 is closed". The real vault
says otherwise; see the correction below. Keep the synthetic numbers only as evidence about
*ranking*, never about *retrieval*.

## Leg V — the real vault overturns two synthetic conclusions

45 hand-labelled queries over the real `personal` vault (3384 notes), same golden-set discipline,
labels written from reading the notes.

```
V/KEYWORD                                    n=45  R@1=0.074 R@5=0.189 nDCG@10=0.166
V/HYBRID                                     n=45  R@1=0.122 R@5=0.296 nDCG@10=0.298
V/SEMANTIC                                   n=45  R@1=0.167 R@5=0.311 nDCG@10=0.345
V/HYBRID reranked                            n=45  R@1=0.274 R@5=0.385 nDCG@10=0.450

V/HYBRID           [bg->bg]  n=14  nDCG@10=0.550     reranked 0.572
V/HYBRID           [en->en]  n=18  nDCG@10=0.317     reranked 0.628
V/HYBRID           [bg->en]  n=7   nDCG@10=0.000     reranked 0.131
V/HYBRID           [en->bg]  n=6   nDCG@10=0.000     reranked 0.000
```

**Read the absolute numbers as a lower bound, not a score.** The golden set labels 1-2 notes per
query out of 3384, and inspection of the misses shows search often returning a *different but
reasonable* note — e.g. for "решихме ли да показваме цените с ДДС" the label is
`memory/fact/ef93172c0cb6.md` while search returns `projects/skb2/docs/pricing-review-2026-04-11.md`.
Sparse labels mechanically depress recall. What is trustworthy is the *comparisons*, since every
mode is scored against the same labels.

### C1 — the reranker is confirmed at scale

nDCG@10 0.298 → 0.450, recall@1 0.122 → 0.274 on a vault the corpus author never saw. Independent
of the synthetic corpus, on 3384 notes. It ships.

### C2 — F2 is CONFIRMED, not resolved

SEMANTIC (0.345) beats HYBRID (0.298) here too, so the first-stage inversion is real and reproduces
at scale — it is not a synthetic artefact. The shipped configuration (hybrid + reranker, 0.450)
still wins overall, but the defect underneath is real and remains unexplained.

### C3 — the reranker does NOT fix cross-lingual, and the synthetic corpus could not have shown that

Cross-lingual on the real vault is **0.000**, reranked included for en→bg. A rank diagnostic against
the whole vault explains why:

```
diag [bg->bg]  n=14  found-in-top-500=13  median rank 1
diag [en->en]  n=18  found-in-top-500=16  median rank 3
diag [bg->en]  n=7   found-in-top-500=2   median rank 29
diag [en->bg]  n=6   found-in-top-500=0   median rank —
```

For 11 of 13 cross-lingual queries the correct note is **not in the top 500 of 3384**. A reranker
only reorders what the first stage retrieved, so it has nothing to work with. Widening the candidate
window cannot fix this either.

**Why the synthetic corpus said the opposite:** it holds 27 notes and the rerank window is 50. The
window covered the entire corpus, so the reranker was always handed the right answer and only had
to order it. That corpus can measure ranking; it is structurally incapable of measuring retrieval.
This is the third time in this work that a correctly-computed number described the wrong subject —
the same pattern as `wrong-subject-numbers`.

**Consequence for the harness:** leg S's floors are ranking floors. Any claim about *recall* has to
come from leg V. Recorded here because the mistake is very easy to repeat.

### C3b — the eval was measuring an embedder this machine does not run

The live engine reports `embedder: local-ollama / bge-m3 / 1024 dim`. Every number above it was
produced with the cached ONNX `multilingual-e5-small`, because the leg-V helper hard-coded it. A
fourth instance of the same failure: correct measurement, wrong subject. Leg V now takes
`-Dsvod.eval.embedder=ollama:<model>`.

Re-measured on the embedder actually in use:

```
V/KEYWORD                     n=45  R@1=0.074 R@5=0.189 nDCG@10=0.180
V/HYBRID                      n=45  R@1=0.163 R@5=0.437 nDCG@10=0.390
V/SEMANTIC                    n=45  R@1=0.189 R@5=0.411 nDCG@10=0.403
V/HYBRID reranked             n=45  R@1=0.256 R@5=0.448 nDCG@10=0.483

                     HYBRID    reranked
  bg->bg             0.500     0.443     <-- WORSE
  en->en             0.468     0.644
  bg->en             0.112     0.267
  en->bg             0.220     0.348
```

Three things follow.

**The reranker is still worth it, but less so:** +0.093 nDCG here against +0.152 on e5-small. It is
not merely compensating for a weak embedder.

**F2 is confirmed a third time, on a third embedder:** SEMANTIC 0.403 > HYBRID 0.390. The
first-stage inversion is not a property of any one model.

**The reranker HURTS Bulgarian→Bulgarian** — nDCG 0.500 → 0.443, recall@1 0.310 → 0.179. This is
the model-research caveat that was recorded as UNVERIFIED at selection time: mmarco-mMiniLMv2 is
fine-tuned on 14 languages and **Bulgarian is not one of them**. Cross-lingual transfer through the
XLM-R backbone is clearly not enough for the language the vault is half written in. The aggregate
hides it; only the per-direction split shows it. Candidate fix is a reranker actually trained on
Bulgarian (`bge-reranker-v2-m3` covers it, at 568M and a latency budget this one was chosen to
avoid) — a measured trade, not a guess, and not taken here.

### C4 — a bigger embedder does not fix it either (refuted, and it would have been expensive)

Ranking the correct note against all 3384, note-level, per model
(`CrossLingualEmbedderTest`, opt-in):

| direction | e5-small median rank | e5-base median rank |
|---|---|---|
| bg→bg | 1 | 1 |
| en→en | 2 | **7** (worse) |
| bg→en | 290 | 115 |
| en→bg | 217 | **491** (worse) |

Twice the parameters, twice the disk, and a full re-embed of every user's vault — for no consistent
gain, and a regression in two of four directions. The "upgrade the embedder" hypothesis is refuted.

Loading e5-base also exposed a real defect in our own code: `OnnxLocalEmbedder` hard-coded
`includeTokenTypes = true`, so any export without a `token_type_ids` input failed with a bare
`Input mismatch, looking for: [input_ids, attention_mask]`. It is now `OnnxConfig.includeTokenTypes`,
because that is a property of the export, not of the architecture — the same landmine already
documented for the reranker, biting in a second place.

### C5 — cross-lingual retrieval is a KNOWN LIMITATION, and the fix is a feature decision

What is established:

- The **cross-encoder can do it** — it scores a Bulgarian query against the matching English passage
  above an unrelated one (`OnnxRerankerTest`).
- The **bi-encoder cannot get the candidate anywhere near the window**, at either model size.
- Widening the window is not viable: catching a median rank of ~200-300 needs a rerank window of
  several hundred pairs, i.e. seconds per query on CPU.

So the remaining options both put a translation step in the search path — translate the query and
search both languages, or index notes in both. Each needs an LLM available at query or index time,
which is a product decision about latency and the offline story, not a defect fix. Not taken here.
Documented instead, with the numbers, so the decision starts from evidence.

## F2 — refuted twice, symptom removed by the second stage

F2 was "HYBRID scores below its own SEMANTIC leg". Both proposed causes turned out to be wrong, and
the sweep that refuted them is kept in `FusionWeightSweepTest` (opt-in, `-Dsvod.eval.sweep`) so the
next person can re-run rather than re-argue.

**Refuted #1 — per-leg weighting.** The documented mechanism said equal RRF weights let a weaker
keyword leg dilute a stronger semantic one. Measured, weighting does essentially nothing:

```
semanticWeight=1.00  nDCG@10=0.786  R@5=0.811   cross-nDCG@10=0.213
semanticWeight=1.50  nDCG@10=0.786  R@5=0.811   cross-nDCG@10=0.213
semanticWeight=2.00  nDCG@10=0.786  R@5=0.811   cross-nDCG@10=0.213
semanticWeight=5.00  nDCG@10=0.803  R@5=0.811   cross-nDCG@10=0.277
```

Identical to three decimals up to 2.0, and even at 5.0 still below SEMANTIC's 0.808, with recall@5
never moving at all. `Rrf.fuse` therefore carries **no** weight parameter — shipping a knob for a
refuted idea is worse than not having tried.

**Refuted #2 — chunk duplication.** The next guess was that fusion returns more chunks from the same
note, so ten hits surface fewer distinct notes. Measured, it is the other way round: HYBRID returns
6.76 distinct notes per 10 chunks, SEMANTIC only 6.30 — and SEMANTIC still has the higher recall.

**What actually resolves it: the reranker.** In the shipped configuration HYBRID beats each of its
own legs:

```
reranked HYBRID    nDCG@10=0.904  R@5=0.905
reranked SEMANTIC  nDCG@10=0.894  R@5=0.905
reranked KEYWORD   nDCG@10=0.743  R@5=0.757
```

`leg S+R` asserts `reranked HYBRID >= max(reranked legs)` **strictly**. The un-reranked path keeps
its `HYBRID_MAY_TRAIL_BEST_LEG_BY = 0.03` tolerance, because there the inversion is real —
and leg V confirms it survives at 3384 notes (C2).

**Still unknown:** *why* first-stage fusion trails its better leg, given that neither weighting nor
chunk duplication explains it. Open, with the tooling in place to attack it. It does not decide
shipped quality, because the second stage sits above it — but calling it "resolved" would be wrong.
