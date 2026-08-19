# Design — Retrieval Quality: eval harness + local reranker

Status: draft
Date: 2026-08-19
Scope: engine (`dev.svod.engine.index`), two units shipped sequentially:
1. **Eval harness** — golden set + IR metrics (recall@k, nDCG@k, MRR)
2. **Local ONNX cross-encoder reranker** — the second stage that is currently stubbed

## Why now

The vector leg is already complete and, on inspection, correct:

- `multilingual-e5-small` (384d), pinned + sha256 (`index/ModelManager.kt:25,37`) — Cyrillic is covered.
- E5 asymmetry honoured: `"query: "` / `"passage: "`, separate `embedQuery` / `embedPassages`
  (`index/OnnxLocalEmbedder.kt:46-47`, `index/Embedder.kt:35-38`).
- HNSW cosine kNN + BM25, fused with RRF (`index/IndexService.kt:519-521`), degrading to keyword
  when the embedder is unavailable.

So the remaining levers are NOT "more vector". They are:

- **We cannot measure retrieval quality.** `grep -rliE "recall@|ndcg|mrr@|precision@" engine/src/test`
  returns nothing. `IndexHybridTest` / `RrfTest` prove the *mechanics* work — that both legs fire and
  that fusion is arithmetically right. Neither can fail because results got *worse*. This is the
  house failure mode from `wrong-subject-numbers`: a metric structurally blind to the failure it
  exists to catch. "361 tests green" says nothing about whether search answers the question.
- **The second stage is a stub.** `NoneReranker` is the default (`index/Reranker.kt:28-31`), and the
  interface's own comment advertises `local-onnx` "when added" — it was never added. The plumbing
  already exists: `rerankTopK = 50` over the fused candidates, `RerankerInfo` for the settings view.

Order matters and is not negotiable: **harness first**. Shipping the reranker without the harness
means tuning by vibes and no way to prove the thing helped — the exact pattern `verify-tests-negatively`
warns about.

## Who needs this

1. **The agent** (Claude via MCP `search` / `context_pack`) — the dominant consumer. Bad ranking is
   paid for twice: wrong context in, wrong answer out, and tokens burned on both.
2. **The human in ⌘K** — interactive, so latency is a hard constraint, not a preference.
3. **Future contributors** — an eval harness is what lets someone change the ranking code without
   the maintainer having to re-read every diff for ranking regressions.

## Narrowest MVP

**Unit 1:** a golden set + a metrics runner that prints recall@{1,5,10}, nDCG@10 and MRR for
KEYWORD / SEMANTIC / HYBRID modes, plus a CI regression gate on the synthetic corpus.

**Unit 2:** one local ONNX cross-encoder provider, opt-in, that measurably beats plain RRF on the
golden set — or does not ship.

## Decisions taken

### D1 — Two golden sets, not one (user-confirmed)

| | Synthetic (committed) | Real (local-only) |
|---|---|---|
| Corpus | ~40-60 authored chunks, bilingual BG/EN, in `engine/src/test/resources` | the live `personal` vault |
| Queries | ~30 with graded relevance labels | ~30-50 real questions |
| Runs in CI | yes — deterministic regression gate | no |
| Committed | yes | **never** — gitignored, path via system property |

Rationale: a synthetic-only set is tuning against the fixture; a real-only set gives no regression
protection and no contributor can run it. Personal notes must not enter a public repo, so the real
set stays outside git entirely.

### D2 — Small multilingual cross-encoder, ~100-150M (user-confirmed)

Target: ~50 pairs under ~300ms on an M-series CPU, in-process via DJL + ONNX Runtime, same pinned
+ sha256 download path as the embedder. Exact repo id chosen from the model research, gated on
verified ONNX artefacts and a permissive licence. Base (~280M) and `bge-reranker-v2-m3` (~568M)
are rejected for interactive search on latency grounds.

### D3 — Opt-in, default off (assumption, stated)

The reranker ships disabled. Rationale: it adds a model download and per-query latency to a search
path that is currently fast, and Svod's failure mode of record is a background job degrading the
foreground. Default stays `NoneReranker`; enabling is an explicit act in Settings. Revisit once the
golden set shows the win and the measured latency on real hardware.

### D4 — Failure is never fatal

A reranker that errors, times out, or has no model must degrade to the fused RRF order, exactly as
the semantic leg already degrades to keyword. Ranking is an optimisation; search staying up is not.

## What would make someone say "whoa"

Answering "did this change make search better or worse?" with a number, in a repo where that
question has so far had no answer at all. The reranker is the first change that gets graded by it.

## How it compounds

The harness outlives the reranker. Every future ranking touch — chunking strategy, RRF weights,
a different embedder, graph-expansion in `context_pack` — inherits a working scoreboard instead of
re-litigating "feels better". That is the difference between a search stack that improves and one
that drifts.

## Non-goals

- Replacing Lucene with a dedicated vector store. HNSW + BM25 in one index is not the bottleneck at
  this corpus size, and splitting it would cost the single-binary, offline, git-backed story.
- LLM-as-judge relevance labelling. Hand-labelled and small beats auto-labelled and unfalsifiable.
- Reranking inside `context_pack` graph expansion. Search path only for now.
