# Architecture — search latency (perf/search-latency)

**Design source:** `claudedocs/research_hnsw-chromadb_2026-08-14.md`
**Scope decision:** only the two cheap wins. Reranker, quantization and contextual
retrieval are explicitly OUT (held pending a recall eval).

## Problem (measured, 2026-08-14, personal vault, 79,178 chunks)

| Path | Measured | Where it goes |
|---|---|---|
| First semantic search after idle | **~0.9–2.4 s** typical, 6.0 s worst observed | Ollama reloads the model (`keep_alive` default `5m`) |
| Warm semantic search | 155–197 ms | **~112 ms embedder** + ~40–50 ms HNSW |
| Keyword (BM25) | 18–44 ms | baseline |

> **On the reload figure.** The 6,061 ms number came from a single fully-cold observation (first
> search of the session, model file not recently read). Controlled unload→load loops with the model
> file warm in the page cache, two runs on the same machine and procedure, measure:
>
> ```
> run A (n=4):  934  940  953  2389 ms
> run B (n=5):  953 1465 1508  1732  1983 ms
> pooled (n=9): range 0.93–2.39 s, median 1,465 ms
> ```
>
> Treat **~1.5 s median** as the expected cost and 6 s as the tail; the 6 s should not be quoted as
> typical. Sized against the warm search in the table above, that is **~5x** at the low end
> (0.93 s vs 155–197 ms) and ~12–15x at the high end (2.39 s) — a real but modest stall, not the
> order-of-magnitude the 6 s figure implied. Every figure here is from the pooled sample; quoting a
> median from one run beside a range from another is how the earlier "40x" error happened.

Plus: `pathsMissingVectors()` scans **all** 79,178 docs and decompresses stored fields
on every call, and is called twice on the boot path (`IndexService.kt:186`, `:234`).

The ANN index is not the bottleneck. Two independent fixes follow.

## Change 1 — keep the Ollama model resident

`POST /api/embed` accepts `keep_alive` (verified against
[ollama/ollama docs/api.md](https://raw.githubusercontent.com/ollama/ollama/main/docs/api.md):
*"controls how long the model will stay loaded into memory following the request
(default: `5m`)"*).

`OllamaEmbedder` gains a `keepAlive: String = "30m"` constructor parameter, serialized
into `EmbedRequest`. No config/contract change — a constructor default, overridable in
tests.

`kotlinx.serialization` omits a property equal to its declared default, so `EmbedRequest`
carries **no** defaults: with one, `keep_alive` would never reach the wire. The pre-existing
`truncate: Boolean = true` had exactly that bug and was silently never sent (harmless only
because Ollama also defaults it to `true`).

**Ollama behaviour worth knowing:** omitting `keep_alive` does *not* reset an already-loaded
model to the `5m` server default — the previously-set window is retained. The model's lifetime
is decided by whatever last touched it.

**Trade-off:** holds the model resident for 30 min after last use — measured via `/api/ps`,
**~0.6 GiB for bge-m3** (1.08 GiB on disk); the larger `multilingual-e5-large` default would be
~2.1 GiB. That removes a ~1.5 s median stall on the first search after an idle period (~5x a normal
semantic search at the low end). Modest but real, and sub-gigabyte residency is cheap here.

**These two changes do not compound — they fix disjoint paths.** A repeat query is served from the
cache without touching Ollama, so `keep_alive` is irrelevant to it; a cache miss against an evicted
model still pays the full reload, which the cache cannot help with. Change 2 is the win on repeats,
Change 1 is the win on the first query after idle.

## Change 2 — cache query embeddings

New `CachingEmbedder` decorator, applied in `Embedders.create()` — the single place the
provider is resolved, so a provider/model swap builds a **new** instance and the cache is
invalidated structurally, with no invalidation logic.

- Caches **`embedQuery` only.** `embedPassages` delegates straight through — chunk texts
  are unique, caching them would only waste heap.
- Bounded LRU (`LinkedHashMap` accessOrder + `removeEldestEntry`), guarded by a lock.
- Returns a **defensive copy**: `FloatArray` is mutable and the cached instance is shared
  across queries; handing out the live array would let any downstream mutation silently
  corrupt every later hit. 4 KB per hit vs the 112 ms it replaces.
- `NoneEmbedder` is not wrapped (`isActive == false` ⇒ embed is never called).

Benefits every provider, not just Ollama — OpenAI pays a full network RTT per query.

## Change 3 — `pathsMissingVectors()` via `FieldExistsQuery`

`FieldExistsQuery` matches docs containing a `KnnFloatVectorField` (verified in the
[Lucene 9.12.0 javadoc](https://lucene.apache.org/core/9_12_0/core/org/apache/lucene/search/FieldExistsQuery.html):
*"A Query that matches documents that contain either a KnnFloatVectorField,
KnnByteVectorField or a field that indexes norms or doc values"*).

Replace the match-all scan with `MUST MatchAllDocs` + `MUST_NOT FieldExistsQuery("vec")`.
Stored fields are then decompressed only for docs that are actually missing a vector —
**zero** in the steady state, instead of all 79,178.

**Equivalence argument:** `upsertFile` (`LuceneIndex.kt:138-141`) adds the `vec` KNN field
and the `vecBytes` stored field inside one `if (cd.vector != null)` block — they are never
written apart, and `writer.addDocument` has exactly one call site. A schema change forces a
full reindex, so no mixed-vintage segments exist. Testing `vec` is therefore equivalent to
testing `vecBytes`, at a fraction of the cost.

Verified empirically against Lucene 9.12.0 (old vs new implementation, identical counts) on
multi-segment indexes where one segment has no `vec` FieldInfo at all, on indexes with deletes
plus re-upsert without vectors, after `forceMerge(1)`, and on the `n == matches == numDocs`
boundary where under-collection would show.

The field name is a named constant (`LuceneIndex.VEC_FIELD`) precisely because this query
detects the backlog by *negation*: a rename missing one site would match nothing, so the
`MUST_NOT` would match everything and the engine would re-embed the whole vault on every boot,
silently.

## Out of scope

Reranker (needs a persistent reranking service), int8 quantization (needs Lucene
9.12.0→9.12.1 for [apache/lucene#13867](https://github.com/apache/lucene/issues/13867)
plus a full reindex), contextual retrieval, Lucene 10/BBQ.
