# ADR-0017 — Non-blocking indexing + pluggable embedding providers

Status: accepted

## Context

On startup the engine bound the App API port only *after* `IndexService.start()` returned, and
`start()` ran the full embedding pass synchronously (`submitBlocking`). A fresh start on the real
personal vault (~2727 notes) re-embedded everything on CPU via ONNX — ~7 cores / 8 GB for 10+
minutes — so the macOS client could not connect until indexing finished. Keyword (BM25) search
needs no embeddings, yet it was held hostage to the embedding pass.

Separately, the embedder was effectively fixed to in-process ONNX (with an Ollama option); there
was no way to point at a remote OpenAI-compatible endpoint (RunPod TEI/Infinity, OpenAI, Together)
or to swap providers at runtime, and a provider/model/dimension change had no clean re-embed path.

## Decision

**Keyword-first, non-blocking, resumable startup.** `start()` returns as soon as the boot job is
scheduled (`indexing.blockStartup`, default `false`). The boot job runs a fast BM25 pass (text +
any reusable vectors, committing in batches) so lexical search is available within seconds, then a
throttled background pass fills in embeddings. The backlog is the set of indexed chunks missing a
vector (`LuceneIndex.pathsMissingVectors`), recomputed from the committed index — so an interrupted
run resumes instead of restarting. Embedding compute runs on a bounded, low-priority pool
(`embedder.maxThreads`, default 2) in batches (`embedder.batchSize`, default 32); all git reads and
Lucene writes stay on the single indexer thread. Periodic commits are resume checkpoints; clean
shutdown checkpoints and the existing `VaultContext.close` ordering releases the vault lock.
`blockStartup=true` preserves the legacy wait-for-full-index behavior (tests, one-shot runs).

**Pluggable embedders selected by config.** `local-onnx` (existing), `local-ollama`, and
`remote-openai` (OpenAI-compatible `/v1/embeddings`). API keys are `Secrets` references only
(`env:`/`file:`/`keychain:`), never raw over the App API. A dimension change wipes the (fixed-width)
vector field and rebuilds keyword-first; a same-dimension model change re-embeds in place. Semantic
search is suppressed (keyword-only) while a provider/model rebuild is in flight, so mixed/stale
vectors are never served.

**Additive App API (contract bumped to 0.8.0).** `GET /settings` and `GET /index/status` gain
embedder + embedding-progress detail; a new `index.progress` WS event; `PUT /embedder`,
`POST /embedder/test`, `POST /index/reembed|pause|resume`. All loopback-only, no auth.

## Consequences

- The macOS client connects immediately; keyword search works at once and semantic results fill in
  the background. Indexing never saturates the machine.
- Two writes per file on a cold build (text-only, then text+vector) — accepted as the cost of
  keyword-first availability; embedding dominates cost, not the extra Lucene upsert.
- Remote embedding endpoints make high-quality models viable without local CPU cost.
- Incremental `onCommit` indexing is unchanged (small diffs embed inline on the indexer thread).
