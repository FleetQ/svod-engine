# Architecture — Graph-aware recall (Ниво 1 + Ниво 2)

Companion to `design-graphrag.md`. Binding on the build. Test plan: `test-plan-graphrag.md`.

---

## 0. Correction to the design doc

The design doc says **Leiden**. The build uses **Louvain** (modularity optimisation, deterministic
iteration order, no new dependency). Rationale: Leiden's advantage is repairing badly-connected
communities in graphs far larger than 3,469 nodes; implementing it means either a new dependency or
~2× the code for no measurable gain at this scale. Louvain is ~200 lines, deterministic when node
order is fixed, and directly testable. Upgrade path stays open — `CommunityDetector` is an interface.

## 1. Package layout

New package `dev.svod.engine.graphrag/` — deliberately **separate** from the existing
`dev.svod.engine.graph/`, which stays the frozen wikilink-navigation layer (`LinkGraph`, `LinkIndex`,
`LinkRewriter`) consumed by `link`/`graph_query`/`GET /api/v1/graph`. No file in `graph/` is modified.

```
graphrag/
  NoteGraph.kt         # note-level graph: nodes + weighted typed edges
  NoteGraphBuilder.kt  # wikilinks + kNN similarity -> NoteGraph
  CommunityDetector.kt # interface + Louvain impl (hierarchical)
  SummaryLlm.kt        # interface + NoneSummaryLlm + OllamaSummaryLlm
  GraphStore.kt        # sidecar persistence at .svod/graph/
  GraphService.kt      # orchestration: background build, status, queries
  GraphModels.kt       # Community, CommunityLevel, GraphStatus, GraphMeta
```

## 2. Data flow

```
                    ┌─ LinkGraph (wikilinks, in-memory, already exists)
NoteGraphBuilder ───┤
                    └─ LuceneIndex.existingVectors(path) ─> mean-pool -> note vector -> top-K cosine

        NoteGraph  ──> Louvain (hierarchical) ──> CommunityLevel[0..n]
                                                        │
                                    SummaryLlm (BUILD TIME ONLY) ──> summary text per community
                                                        │
                                              GraphStore -> .svod/graph/*.json
                                                        │
        query time (NO LLM) ──────────────────────────> read summaries, rank by cosine vs query embedding
```

## 3. Edge construction (D1)

Two edge kinds in one undirected weighted graph:

| Kind | Source | Weight |
|---|---|---|
| `LINK` | `LinkGraph.edges()` — resolved wikilinks only | `1.0` (both directions; multi-links collapse) |
| `SIM` | top-K cosine neighbours over note vectors | `cosine`, kept only if `>= simThreshold` |

**Note vectors cost zero embedder calls.** `LuceneIndex.existingVectors(path)` reads back the stored
`VEC_BYTES_FIELD` written at index time. Per note: mean-pool its chunk vectors, L2-normalise, **discard
the chunk vectors immediately**. Holding all 79,178 chunk vectors at 1024 dims would be ~316 MB;
accumulating per note and releasing keeps the peak at ~14 MB for 3,469 note vectors.

**kNN is brute force** — 3,469² / 2 ≈ 6M cosine ops, single pass, seconds on a background thread. No
new ANN structure; the sidecar is not a search index.

A note with no vectors yet (background embedding still running) contributes `LINK` edges only. This is
correct, not a bug: the graph improves as embedding completes, and `graphStatus` reports the coverage.

## 4. Community detection

`CommunityDetector.detect(graph): List<CommunityLevel>` — Louvain run repeatedly, each level
contracting the previous. Level 0 = finest, last level = coarsest. Determinism is required for the
tests: nodes are iterated in **sorted path order**, ties broken by path, no RNG.

Communities smaller than `minCommunitySize` (default 3) are not summarised — they are noise, and each
one would otherwise cost an LLM call.

## 5. Summaries — the only LLM, and only at build time

```kotlin
interface SummaryLlm {
    val provider: String
    val isActive: Boolean
    suspend fun summarise(prompt: String): String?   // null = unavailable, never throws upward
}
```

- `NoneSummaryLlm` — `provider = "none"`, `isActive = false`, returns `null`. **Default.**
- `OllamaSummaryLlm` — POSTs `/api/generate` to the endpoint already configured for embeddings
  (`EmbedderSettings.ollamaEndpoint`), own model, own timeout, `keep_alive` reused.

Mirrors `Embedders.create` / `Rerankers.create`: `SummaryLlms.create(config)`.

The prompt carries each member note's **path + first heading + a bounded excerpt**, capped at
`summaryInputChars` so a large community cannot overrun the model's context. Excerpts come from the
**chunk stream**, which is already `<private>`-stripped (`MarkdownChunker.stripPrivateSpans`) — so the
leak guard applies for free. This is a binding constraint, not an optimisation: **never read raw files
for summary input.**

If `summarise` returns `null`, the community is stored with `summary = null` and a machine-generated
fallback label (top member paths). The graph remains fully usable without any LLM.

## 6. Persistence — sidecar only

`.svod/graph/` next to `.svod/index` (which is **never touched**):

| File | Contents |
|---|---|
| `meta.json` | `{ version, head, builtAt, noteCount, edgeCount, vectorCoverage, llmProvider }` |
| `graph.json` | nodes + edges (kind, weight) |
| `communities.json` | levels → communities → member paths |
| `summaries.json` | communityId → `{ title, summary, model }` |

Whole-directory rewrite on rebuild (atomic: write to `.tmp`, then move). Deleting `.svod/graph/`
is always safe and simply disables the feature until the next build. `head` is the git HEAD the
build ran against — the staleness signal.

## 7. Wiring

`VaultContext.open` gains, after `index.start()`:

```kotlin
val gc = config.toGraphConfig()
val graph = GraphService(vault.resolve(".svod").resolve("graph"), engine, index,
                         SummaryLlms.create(gc), gc)
graph.start()   // no-op unless gc.enabled; never blocks startup
```

`VaultView` gains `val graph: GraphService`. `VaultContext.close()` closes it **before** the index
(it reads from the index). Build runs on a named `MIN_PRIORITY` daemon thread, mirroring
`svod-index-boot` / `svod-index-rebuild`; `svod-graph-build`. Pausable and cancellable on close.

## 8. Config (default off)

```kotlin
val graph: GraphSettings = GraphSettings(),

data class GraphSettings(
    val enabled: Boolean = false,          // master switch, default OFF
    val simEdgesPerNote: Int = 6,
    val simThreshold: Double = 0.75,
    val minCommunitySize: Int = 3,
    val summaryProvider: String = "none",  // "none" | "ollama"
    val summaryModel: String = "qwen2.5:7b-instruct",
    val summaryEndpoint: String? = null,   // defaults to the embedder's Ollama endpoint
    val summaryInputChars: Int = 12_000,
    val summaryTimeoutSeconds: Int = 120,
    val rebuildOnStartup: Boolean = false,
)
```

## 9. Ниво 1 — graph expansion in recall

Two changes, both additive and both degradable.

**9a. `context_pack(graphExpand = true)`** (`SvodTools.contextPack`). After the existing dedup loop
fills blocks, if `graphExpand` and budget remains: take the top `expandFrom` block paths, collect their
1-hop `LinkGraph` neighbours (outlinks + backlinks) not already packed, and append them in neighbour-
degree order until the budget is exhausted. Each expanded block is marked `"viaGraph": true` and carries
`"viaPath"` (which hit pulled it in) so the agent can tell primary evidence from context.

Default `false`. Wrapped in `runCatching` — a graph failure leaves the original blocks untouched.

**9b. Backlink tie-break — DROPPED during build.** The plan was an optional `graphSignal` in
`IndexService` that broke exact fused-score ties by backlink count. It was cut because it
**contradicts safety invariant A3** ("search results unchanged when the graph is on"), which is the
stronger guarantee and the one the operator actually asked for. Its upside was marginal — reordering
only exact ties — while its cost was a change to the hottest path in the engine.

Consequence: `search()` is provably untouched by this feature. All Ниво 1 value comes from 9a.
`maybeRerank` is left alone.

## 10. Ниво 2 — query surface (LLM-free)

`GraphService.communities(query: String?, level: Int?, limit: Int)`:
- no `query` ⇒ communities at `level` (default coarsest), deterministic by size then id;
- with `query` ⇒ embed the query with the **existing** embedder, cosine against each community's
  centroid (mean of member note vectors, stored at build time), return top `limit`.

Returns summaries as **evidence**. The engine does not synthesise an answer — the agent does. This is
what keeps query time LLM-free.

## 11. Surfaces

**MCP** (`SvodMcpServer`, same `tool(...)` registration):
- `graph_communities` — `{query?, level?, limit?}` → communities + summaries + member paths.
- `graph_status` — build state, head, staleness, coverage.
- `context_pack` gains `graphExpand: boolean`.

**App API** (`AppApiServer`, per-vault routes):
- `GET  /api/v1/graph/communities?query=&level=&limit=`
- `GET  /api/v1/graph/status`
- `POST /api/v1/graph/rebuild`

The existing `GET /api/v1/graph` is untouched.

**Contract:** `0.23.0 → 0.24.0`, additive only. `ApiCompatibility.CURRENT_CONTRACT_VERSION` is the
single authority (`VersionConsistencyTest` guards drift — see `mem:svod-engine-deploy-launchd`).

## 12. macOS UI (D3)

Scoped to the existing Graph feature; `Features/Graph/` + `App/GraphModel.swift` only.

- `GraphModel` gains `@Published communities: [Community]`, `selectedCommunity`, `communitiesLoading`,
  and `loadCommunities()`.
- `GraphView` gains a leading **"Теми"** list pane: community title, member count, summary on select.
  Selecting a community scopes the existing graph render to its members (reuses `Scope`), and a member
  row opens the note via `app.selectedPath`.
- Feature-detect: if `/graph/status` 404s (older engine), the pane is hidden entirely. The app must
  keep working against engine v1.14.1.

`SvodClient` protocol + DTOs extended additively; `MockSvodClient` gains fixtures so previews and
offline builds keep working.

## 13. Failure modes and their handling

| Failure | Handling |
|---|---|
| No vectors yet (embedding in flight) | `LINK`-only graph; coverage reported in status |
| Ollama down / model missing | `summarise` → `null` → fallback labels; build still succeeds |
| `.svod/graph/` corrupt or partial | Load fails → treated as "never built"; rebuild clears it |
| HEAD moved since build | `stale: true` in status; results still served |
| Build throws | State `ERROR` + message in status; **search and context_pack unaffected** |
| Community too large for the model | Excerpts truncated to `summaryInputChars` before the call |

## 14. Explicitly out of scope

- LLM entity/relation extraction (Ниво 3) — not built, not stubbed.
- Any synthesis of a final answer inside the engine.
- Incremental per-write graph update — rebuild is explicit or interval-based; staleness is *surfaced*.
- Vaults other than `personal` in this sprint (config is per-vault, so others simply stay `enabled=false`).
- Writing anything into the vault.
