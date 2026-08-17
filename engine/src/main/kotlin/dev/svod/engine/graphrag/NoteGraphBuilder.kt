package dev.svod.engine.graphrag

import dev.svod.engine.graph.LinkGraph
import dev.svod.engine.index.IndexService

/**
 * Builds the note-level [NoteGraph] from two sources that cost **nothing extra**:
 *  - human-authored `[[wikilinks]]`, via the existing [LinkGraph];
 *  - inferred similarity, via chunk vectors already stored in the Lucene index.
 *
 * No embedder call and no LLM call happens here. That is the whole point of the Ниво 2 design: the
 * expensive part of GraphRAG is inferring a graph from unstructured text, and Svod already has most
 * of one (`design-graphrag.md` §4, D1).
 *
 * Measured motivation for the similarity leg: only **665 of 3,469 notes (19%)** carry a wikilink, so a
 * wikilink-only graph would leave four fifths of the corpus unreachable to a thematic query.
 */
class NoteGraphBuilder(
    private val index: IndexService,
    private val simEdgesPerNote: Int,
    private val simThreshold: Double,
) {

    /** [vectors] is returned so the caller can compute community centroids without re-reading Lucene. */
    data class Result(
        val graph: NoteGraph,
        val vectors: Map<String, FloatArray>,
        val vectorCoverage: Double,
    )

    /**
     * @param onProgress called with a short human-readable phase label, for `graphStatus`.
     * @param isCancelled polled between phases and inside the O(n²) sweep so a shutdown or an
     *        explicit rebuild does not have to wait for a full pass.
     */
    fun build(
        linkGraph: LinkGraph,
        paths: Set<String>,
        onProgress: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result {
        val nodes = paths.toSortedSet()
        val edges = ArrayList<NoteEdge>()

        onProgress("link edges")
        // Only resolved wikilinks, and only between notes that are actually indexed — a link into a
        // `private: true` note must not resurrect it as a graph node.
        for ((src, dst) in linkGraph.edges()) {
            if (src in nodes && dst in nodes && src != dst) edges.add(NoteEdge(src, dst, EdgeKind.LINK, LINK_WEIGHT))
        }
        val linkEdgeCount = edges.size

        onProgress("note vectors")
        val ordered = ArrayList<String>(nodes.size)
        val pooled = ArrayList<FloatArray>(nodes.size)
        var dim = 0
        for (p in nodes) {
            if (isCancelled()) return partial(nodes, edges, ordered, pooled, paths.size)
            val v = index.noteVector(p) ?: continue
            if (dim == 0) dim = v.size
            if (v.size != dim) continue // a mid-flight embedder swap can leave mixed dimensions
            ordered.add(p)
            pooled.add(v)
        }
        val coverage = if (nodes.isEmpty()) 0.0 else ordered.size.toDouble() / nodes.size

        if (ordered.size >= 2 && simEdgesPerNote > 0) {
            onProgress("similarity edges")
            edges.addAll(similarityEdges(ordered, pooled, dim, isCancelled))
        }

        val graph = NoteGraph.of(nodes, edges)
        return Result(graph, ordered.indices.associate { ordered[it] to pooled[it] }, coverage)
            .also { onProgress("built: ${graph.nodes.size} nodes, $linkEdgeCount link + ${graph.simEdgeCount} sim edges") }
    }

    /**
     * Brute-force top-K cosine neighbours.
     *
     * Deliberately not an ANN structure: at ~3.5k notes this is ~6M dot products in a single
     * background pass, and the sidecar is a clustering substrate, not a query index. Vectors arrive
     * L2-normalised from [IndexService.noteVector], so cosine IS the dot product.
     *
     * Vectors are packed into one flat array for cache locality — an array-of-arrays walk over 3.5k ×
     * 1024 floats spends most of its time chasing pointers.
     */
    private fun similarityEdges(
        paths: List<String>,
        vectors: List<FloatArray>,
        dim: Int,
        isCancelled: () -> Boolean,
    ): List<NoteEdge> {
        val n = paths.size
        val flat = FloatArray(n * dim)
        for (i in 0 until n) System.arraycopy(vectors[i], 0, flat, i * dim, dim)

        val out = ArrayList<NoteEdge>(n * simEdgesPerNote)
        // Min-heap of the best K so far, ordered by score ascending so the weakest is cheapest to evict.
        val best = java.util.PriorityQueue<Pair<Int, Double>>(simEdgesPerNote + 1, compareBy { it.second })

        for (i in 0 until n) {
            if (i % 256 == 0 && isCancelled()) break
            best.clear()
            val base = i * dim
            for (j in 0 until n) {
                if (j == i) continue
                var dot = 0.0
                val other = j * dim
                for (k in 0 until dim) dot += flat[base + k] * flat[other + k]
                if (dot < simThreshold) continue
                if (best.size < simEdgesPerNote) {
                    best.add(j to dot)
                } else if (dot > best.peek().second) {
                    best.poll()
                    best.add(j to dot)
                }
            }
            for ((j, score) in best) out.add(NoteEdge(paths[i], paths[j], EdgeKind.SIM, score))
        }
        // kNN is asymmetric (i may be in j's top-K without the converse). NoteGraph.of symmetrises and
        // collapses duplicates, keeping the stronger weight — so no extra work is needed here.
        return out
    }

    private fun partial(
        nodes: Set<String>,
        edges: List<NoteEdge>,
        ordered: List<String>,
        pooled: List<FloatArray>,
        total: Int,
    ): Result = Result(
        NoteGraph.of(nodes, edges),
        ordered.indices.associate { ordered[it] to pooled[it] },
        if (total == 0) 0.0 else ordered.size.toDouble() / total,
    )

    private companion object {
        /**
         * A wikilink outweighs any similarity edge (max cosine is 1.0 and the threshold keeps SIM
         * edges below it in practice), so human intent dominates clustering where it exists.
         */
        const val LINK_WEIGHT = 1.5
    }
}
