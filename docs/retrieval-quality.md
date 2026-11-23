# Retrieval quality: measure search on your own vault

```sh
svod-engine eval <config.json> <golden.jsonl>
```

`svod-engine eval` runs a set of queries you wrote, each with the notes that should come back,
against the vault in your config, and prints how well search finds them: recall@1, recall@5,
recall@10, nDCG@10 and MRR. It scores each search mode (HYBRID, KEYWORD, SEMANTIC) and each
group of your golden file, using the embedder and reranker from the same config the engine uses.

## What it does and does not do

- **The engine must be stopped.** Eval opens the vault's search index under `.svod/index`, which
  only one process can hold. If the engine is running, eval stops with
  `index is in use — stop the engine first`.
- **No notes are written and nothing is committed.** Eval does not open the vault for writing and
  takes no vault lock.
- **The index may catch up to HEAD.** Like an engine start, eval first brings the index in line
  with the vault's latest commit, so notes committed since the engine last ran are indexed (and
  embedded, when an embedder is configured). That is the only thing it writes.
- **A local model may be downloaded.** With `embedder.provider` (or `reranker.provider`)
  `onnx-local` and no model on disk yet, the first run downloads it, exactly as an engine start does.
- With `embedder.provider: none` the SEMANTIC mode is skipped with a note; HYBRID is then
  keyword-only.
- Paths in `gains` that are not notes at the vault's HEAD are reported as warnings: a mistyped path
  can never be found and would quietly pull recall down.
- Queries whose recall@5 is 0 are listed with what was expected and the top 5 that came back.

## Golden file format

One JSON object per line. See [`examples/golden.sample.jsonl`](../examples/golden.sample.jsonl).

```
# Section 1 - en
{"q": "how do I deploy the engine", "gains": {"ops/deploy.md": 3, "ops/launchd.md": 1}}
# Section 2 - bg
{"q": "как се деплойва engine-ът", "gains": {"ops/deploy.md": 3}}
```

- `q` is the query, as you would type it.
- `gains` maps a note path (relative to the vault root) to a relevance grade:
  3 = the note that answers the question, 2 = a strong match, 1 = related and fine to see,
  0 = not relevant (the same as leaving the path out). Each query needs at least one grade above 0.
- Blank lines and `#` lines are skipped. A `# Section ...` line starts a group: every query below it
  is also reported on its own, so a weak subset (another language, a harder kind of question) is
  not hidden by the average.
- A line that is not valid JSON, has no `q` or `gains`, or has only 0 grades stops the run with its
  line number.

Golden sets built from real notes are private. Keep the file outside any public repository.

## Reading the numbers

Search returns chunks; eval collapses them to notes (first hit of a note wins), so every metric is
**scored per note, not per chunk**. Each metric is **macro-averaged**: every query weighs the same.

| Metric | Meaning |
|---|---|
| R@k (recall@k) | Share of a query's relevant notes (grade above 0) found in the top k notes. |
| nDCG@10 | Ranking quality of the top 10 notes with gain `2^grade - 1`, so a grade-3 note near the top counts far more than a grade-1 note. 1.0 is the ideal order. |
| MRR | 1 / rank of the first relevant note (0 when none is returned). |

## Baseline: the synthetic corpus (leg P)

These are the numbers of the test suite's leg P: the committed bilingual synthetic corpus
(`GoldenCorpus`, 27 notes, 37 queries) with a hermetic bag-of-words embedder and no reranker.
`RetrievalQualityDocsTest` runs leg P and fails when this table no longer matches it.

The docs test does not build the index exactly as `RetrievalEvalTest` does: it uses one embedding
worker, then closes and reopens the index before measuring. The reason is a hypothesis, not a proven
cause. With several workers, the order in which notes are re-written may decide how equal BM25
scores are ordered, and Lucene keeps counting deleted copies until a background merge removes them.
Either could move the third decimal. The evidence is one observation during development: with the
default setup, one run that shared the JVM with `RetrievalEvalTest` measured KEYWORD nDCG@10 at
0.698 instead of 0.694 (MRR 0.678 instead of 0.677). An independent re-run did not reproduce this.
With the one-worker build, the test matched the table in every run combination tried.
`RetrievalEvalTest` only checks floors, so it is not affected either way.

| Run | R@1 | R@5 | R@10 | nDCG@10 | MRR |
|---|---|---|---|---|---|
| P/HYBRID | 0.365 | 0.703 | 0.743 | 0.600 | 0.563 |
| P/KEYWORD | 0.514 | 0.730 | 0.770 | 0.694 | 0.677 |
| P/SEMANTIC | 0.203 | 0.568 | 0.689 | 0.471 | 0.411 |

Leg P checks that the pipeline (BM25, fusion, filters) did not regress. Its embedder only sees
shared words, so its SEMANTIC row says nothing about real embedding quality.

### With real models (not test-checked)

[architecture-retrieval-quality.md](architecture-retrieval-quality.md) records runs on the same
corpus with the real `multilingual-e5-small` embedder: HYBRID nDCG@10 0.786 (recall@5 0.811), and
with the `mmarco-mMiniLMv2-L12-H384-v1` reranker over the top 50 candidates nDCG@10 0.904 (recall@1
0.622 → 0.743). These need the downloaded models, do not run in CI and are not checked by a test.
The same page shows that a real vault scores much lower than the synthetic corpus — which is why
you should measure your own.

## How to reproduce

Leg P and the baseline table, from `engine/`:

```sh
./gradlew test --tests 'dev.svod.engine.index.RetrievalEvalTest' --tests 'dev.svod.engine.index.RetrievalQualityDocsTest'
```

Then on your own vault, with the engine stopped:

```sh
svod-engine eval ~/svod/config.json golden.jsonl
```
