# ADR-0003 — Hybrid index: chunking, RRF fusion, schema & versioning

- Status: **Accepted**
- Date: 2026-06-12
- Scope: Build step 2 (Lucene hybrid index + embeddings + self-heal)

## Context

Agents need to retrieve from the vault by keyword *and* by meaning, over multilingual
content (Latin + Cyrillic), without ever blocking the single-writer integrity core or
drifting out of sync with git. The index must be cheap to keep current and trivial to
rebuild, because git — not the index — is the source of truth.

## Decisions

### 1. The index is derived state, an exact function of git HEAD
The indexer reads **committed blobs only** (via its own read-only jgit handle,
`GitReader`), never the working tree. So the index for a given HEAD is deterministic and
fully reconstructible. `meta.json` records the last-indexed commit; on open the index
catches up (incremental diff) or, if its metadata is missing/incompatible, rebuilds from
HEAD. A wiped index directory self-heals to byte-identical search results on next open.

### 2. Chunking at heading granularity, with content-hash reuse
A document is split into sections: a preamble chunk (content before the first heading)
plus one chunk per ATX heading, running until the next heading. Each chunk carries a
SHA-256 `contentHash` of `heading + text`. On reindex we diff new chunks against the
chunks already stored for that path and **reuse the embedding** of any chunk whose hash is
unchanged — so editing one section re-embeds exactly one chunk, not the whole file.
Embedding (the Ollama round-trip) is the expensive part; this is where incrementality
pays off. *(Proven by test: 3 sections → edit 1 → exactly 1 re-embed.)*

### 3. BM25 + HNSW kNN, fused with RRF (k=60)
Both signals live in one Lucene document per chunk: an analyzed `text`/`heading` field for
BM25 and a `KnnFloatVectorField` (cosine) for semantic kNN. Results are combined with
**Reciprocal Rank Fusion**, score = Σ 1/(k + rank), k=60 (Cormack et al. default).

- Why RRF over a weighted score blend: BM25 scores and cosine similarities live on
  incomparable scales; normalizing them is fragile and corpus-dependent. RRF is
  rank-based, so it needs no tuning and no per-query calibration. k=60 is the well-known
  default; it is a single knob we can revisit if needed, not a weight matrix.
- Filters (tag / path-prefix / created-date-range) are applied as Lucene `FILTER` clauses
  to **both** legs before fusion, so constraints hold regardless of retrieval path.

### 4. Embeddings: Ollama + multilingual-e5-large, asymmetric prefixes
`OllamaEmbedder` calls `/api/embed` with e5's `passage:` / `query:` prefixes and probes
its dimension (1024) once at construction so Lucene can size the vector field. The
`Embedder` interface keeps the index model-agnostic; tests use a deterministic fake, and a
live-Ollama integration test (auto-skipped when unavailable) proves true semantic and
cross-lingual retrieval.

### 5. Versioned index; model change ⇒ migration
`meta.json` stores `{schemaVersion, embeddingModel, embeddingDim, headCommit}`. If the
configured schema/model/dim is incompatible with what's stored, the index is wiped and
fully rebuilt from HEAD. This covers both a model swap (vectors no longer comparable) and
a dimension change (segments must be rewritten) safely.

### 6. Indexing is off the write path
All index mutations run on one dedicated `svod-indexer` thread. The engine exposes an
`onCommit` listener; the indexer's handler only *enqueues* a sync and returns, so a write
never waits on embedding or Lucene I/O. *(Proven deterministically: with the embedder
gated/blocked, 60 concurrent writes still all succeed and the step-1 integrity invariants —
tree==HEAD, `git fsck` clean — hold; the index catches up once unblocked.)*

## Consequences

- Search is BM25-quality on exact terms and semantic on paraphrase/synonym/cross-lingual.
- Rebuild-from-HEAD is the universal repair: corruption, schema bumps, model swaps all
  resolve to the same reconcile path.
- The index adds zero latency and zero failure surface to writes; an indexer crash can
  only make search *stale*, never lose data or block agents.

## Alternatives considered

- **Weighted score fusion (α·BM25 + β·cosine)** — needs score normalization and per-corpus
  tuning; brittle. Rejected for RRF.
- **Whole-file (no chunking)** — coarse retrieval and forces re-embedding the entire file
  on any edit. Rejected.
- **Fixed-size token windows** — ignores document structure and splits mid-thought;
  heading sections align with how notes are actually written. Rejected.
- **Index inside the write actor** — couples slow embedding to the write path and risks the
  integrity guarantees. Rejected for the off-path indexer.
- **Lucene 10** — newer kNN codecs, but requires JDK 21 (absent here). Pinned to 9.12 on
  JDK 20; revisit when the toolchain moves to 21.
