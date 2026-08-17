package dev.svod.engine.graphrag

import kotlinx.serialization.Serializable

/**
 * An undirected, weighted, note-level graph — the substrate community detection runs on.
 *
 * Deliberately NOT the same thing as [dev.svod.engine.graph.LinkGraph]: that one is a directed
 * wikilink navigation surface (outlinks/backlinks for a single note) and stays frozen. This one is
 * symmetric, weighted, includes inferred similarity edges, and exists only to be clustered.
 *
 * Nodes are note paths. Every node in [nodes] is present even if it has no edges — an isolated note
 * is a legitimate one-member community, not something to drop, or the levels would stop partitioning
 * the corpus.
 */
@Serializable
class NoteGraph(
    val nodes: List<String>,
    val edges: List<NoteEdge>,
) {
    // Body properties are not serialized by kotlinx.serialization — only the constructor pair is.

    /** path -> dense index, for the array-based Louvain inner loop. */
    private val indexOf: Map<String, Int> = nodes.withIndex().associate { (i, p) -> p to i }

    /**
     * Adjacency as dense indices: `adjacency[i]` = list of (neighbour index, weight). Built once;
     * Louvain touches it in its hot loop, so it must not be recomputed per pass.
     */
    val adjacency: Array<MutableList<Pair<Int, Double>>> = Array(nodes.size) { mutableListOf<Pair<Int, Double>>() }
        .also { adj ->
            for (e in edges) {
                val i = indexOf[e.a] ?: continue
                val j = indexOf[e.b] ?: continue
                if (i == j) continue // a self-loop carries no community information
                adj[i].add(j to e.weight)
                adj[j].add(i to e.weight)
            }
        }

    /** Sum of incident edge weights per node (Louvain's `k_i`). */
    val degrees: DoubleArray = DoubleArray(nodes.size) { i -> adjacency[i].sumOf { it.second } }

    /** Total edge weight (Louvain's `m`); 0.0 for an edgeless graph. */
    val totalWeight: Double = degrees.sum() / 2.0

    fun indexOf(path: String): Int? = indexOf[path]

    val linkEdgeCount: Int get() = edges.count { it.kind == EdgeKind.LINK }
    val simEdgeCount: Int get() = edges.count { it.kind == EdgeKind.SIM }

    companion object {
        /**
         * Canonical edge construction: orders the endpoints, drops self-edges, and collapses
         * duplicates by keeping the STRONGEST edge — with [EdgeKind.LINK] winning ties, so a
         * human-authored link is never masked by an inferred one between the same pair.
         */
        fun of(nodes: Collection<String>, rawEdges: Collection<NoteEdge>): NoteGraph {
            val best = HashMap<Pair<String, String>, NoteEdge>(rawEdges.size)
            for (e in rawEdges) {
                if (e.a == e.b) continue
                val key = if (e.a <= e.b) e.a to e.b else e.b to e.a
                val norm = NoteEdge(key.first, key.second, e.kind, e.weight)
                val cur = best[key]
                if (cur == null || norm.beats(cur)) best[key] = norm
            }
            return NoteGraph(
                nodes = nodes.toSortedSet().toList(),
                // Sorted so a rebuild over an unchanged corpus is byte-identical (test B5).
                edges = best.values.sortedWith(compareBy({ it.a }, { it.b })),
            )
        }

        private fun NoteEdge.beats(other: NoteEdge): Boolean =
            if (kind != other.kind) kind == EdgeKind.LINK else weight > other.weight
    }
}
