# Design — Graph-aware recall (Ниво 1 + Ниво 2)

**Status:** approved for build · **Date:** 2026-08-17 · **Contract impact:** additive
**Explicit non-goal:** Ниво 3 (LLM entity extraction over all chunks). Not in this sprint, not behind a flag, not scaffolded for.

---

## 1. The problem

`IndexService.search` (BM25 + kNN → RRF → optional reranker) answers *"find the note where I wrote X"*
excellently — ~20 ms warm on repeat queries. It cannot answer three classes of question, and no amount
of tuning will change that, because it returns top-K chunks and has no representation of the corpus as
a whole:

1. **Global / thematic** — "what are the recurring themes in my notes about X", "summarise everything
   I decided about auth this year".
2. **Multi-hop** — "which decisions depend on the fleetq-01 migration", where the chain is not written
   down in any single note.
3. **Neighbourhood-aware recall** — a hit's *context* (what links to it, what it links to) never
   influences what gets packed into an agent's context window.

Today the link graph exists (`graph/LinkGraph.kt`) but is a **navigation surface only** — consumed by
the MCP `link`/`graph_query` tools and the App API graph endpoints. It does not participate in
retrieval at all.

## 2. Who needs this

- **Agents over MCP** (the primary consumer). `context_pack` is the agent-memory recall primitive; it
  currently packs isolated chunks with no relational context.
- **The operator, in the macOS app** — thematic browsing of a 3,469-note vault that no longer fits in
  one head.

## 3. Measured starting conditions

| Fact | Value | Source |
|---|---|---|
| Notes (all vaults) | 3,469 `.md` | `find ~/Svod -name '*.md'`, 2026-08-17 |
| Indexed chunks | ~79,178 | engine index `docCount`, last check |
| Notes with ≥1 wikilink | **665 (19%)** | `grep -rlo '\[\[…\]\]'`, 2026-08-17 |
| Wikilink occurrences | 2,822 | same |
| Notes with `type:`/`tags:` frontmatter | 808 (23%) | `grep -rl '^type:\|^tags:'` |
| `personal` index on disk | 681 MB Lucene + 129 MB models | `du -sh ~/Svod/personal/.svod/*` |

**The 19% is the load-bearing number.** A graph built from wikilinks alone leaves 4/5 of the vault
isolated, so global queries would see 1/5 of the corpus. This is why edges are not wikilink-only.

## 4. Decisions taken

| # | Decision | Rationale |
|---|---|---|
| D1 | **Edges = wikilinks + kNN similarity** | 100% note coverage with **zero** LLM calls and **zero** new embedder calls — `LuceneIndex.existingVectors(path)` reads stored chunk vectors back, which mean-pool to a note vector. |
| D2 | **Pluggable `SummaryLlm` interface, Ollama first** | Mirrors the existing `Embedder`/`Reranker` provider shape (`provider = "none"` default). Content never leaves the machine. |
| D3 | **Engine + macOS UI in the same sprint** | Operator's call, overriding the engine-only recommendation. Cost: a second repo and a signed/notarized release. |
| D4 | **`personal` vault first** | Largest and best-connected. Validated against a **copy**, never the live vault. |

## 5. MVP — the narrowest thing worth shipping

**Ниво 1** — graph-aware recall, no new storage, no LLM:
- `context_pack` expands its ranked hits by 1 hop over the existing `LinkGraph` before token-budget
  filling, so a packed note brings its immediate relational context.
- Backlink count becomes a ranking signal for tie-breaking among fused candidates.

**Ниво 2** — thematic layer:
- A note-level graph (D1) persisted in a **sidecar** at `.svod/graph/`, never touching the 681 MB
  Lucene index.
- Hierarchical community detection over ~3.5k nodes — milliseconds. (**Louvain**, not Leiden; see
  `architecture-graphrag.md` §0 for why.)
- One LLM-generated summary per community (~50–200 calls total, at **build time**), stored as text in
  the sidecar.
- A `graph_communities` / global-query surface that reads pre-computed summaries.

**Query time stays LLM-free.** The engine's invariant — "fully functional with no LLM at all"
(`FactClassifier.kt:24`) — survives untouched.

## 6. What would make someone say "whoa"

Asking *"какви теми се повтарят в бележките ми за X"* and getting a synthesised answer with citations,
from a vault that was never manually organised for it — on a machine that is not sending anything
anywhere.

## 7. How it compounds

- The note-level graph is reusable infrastructure: once it exists, entity extraction (Ниво 3), "related
  notes" in the editor, orphan detection, and duplicate clustering are all short additions rather than
  new subsystems.
- Community summaries are themselves notes-shaped, so they can eventually be *promoted* into the vault
  as real notes via the existing `promote` path.
- Every measurement in §3 becomes a regression baseline.

## 8. The unsolved problem, stated honestly

**Incremental staleness.** One edited note can re-shape a community and invalidate its summary. MS
GraphRAG is designed for static corpora and has no good answer; neither do we.

**Our position:** a stale graph is acceptable **if it is visible**. `graphStatus` is surfaced exactly
like the existing `embeddingStatus()`, so staleness is *known*, never silently wrong. Rebuild is
explicit (or interval-based), not per-write. This is a deliberate trade, not an oversight.

## 9. Safety envelope (binding on every phase)

1. Sidecar `.svod/graph/` only — **no** change to the Lucene schema (would force a reindex of 79,178
   chunks; cold start is already 25 s–7.5 min).
2. `graph: GraphSettings(provider = "none")` — default **off**, like `RerankerSettings`.
3. Any graph stage in the search path is wrapped and **degrades to the fused order**, mirroring
   `maybeRerank` / `semanticLeg`. A graph failure must never error a search.
4. Background build on `Thread.MIN_PRIORITY`, pausable, status-surfaced — mirroring the embedding pass.
5. Contract changes **additive only**; app v0.2.15 in the field must keep working against the new engine.
6. **Read-only on the vault.** No writes to notes.
