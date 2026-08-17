package dev.svod.engine.graphrag

/** One partition of the corpus: a list of communities, each a list of note paths. */
typealias Partition = List<List<String>>

/**
 * Splits a [NoteGraph] into a hierarchy of communities, finest level first.
 *
 * An interface, not a function, because the choice of algorithm is a documented trade
 * (`architecture-graphrag.md` §0): Louvain now, Leiden available later without touching callers.
 */
interface CommunityDetector {
    fun detect(graph: NoteGraph): List<Partition>
}

/**
 * Louvain modularity optimisation, run repeatedly with graph contraction between passes.
 *
 * **Determinism is a requirement, not a nicety** — the sidecar is compared byte-for-byte across
 * rebuilds (tests B5/C1) and community ids must be stable so a UI selection survives a rebuild of an
 * unchanged vault. Therefore: nodes are visited in index order (sorted path order at level 0), ties
 * go to the node's current community and then to the lowest community index, and there is no RNG.
 *
 * At ~3.5k nodes each pass is milliseconds, which is why no sampling or approximation is needed.
 */
class LouvainDetector(
    /** Safety bound on local-moving sweeps within one pass; convergence is normally far sooner. */
    private val maxSweeps: Int = 50,
    /** Safety bound on contraction passes (hierarchy levels). */
    private val maxLevels: Int = 20,
) : CommunityDetector {

    override fun detect(graph: NoteGraph): List<Partition> {
        val n = graph.nodes.size
        if (n == 0) return emptyList()

        // An edgeless graph has no community structure to find. One level of singletons is the honest
        // answer and preserves the "every level partitions all nodes" invariant (test C3).
        if (graph.totalWeight <= 0.0) return listOf(graph.nodes.map { listOf(it) })

        var work = Working.from(graph, maxSweeps)
        // For each ORIGINAL node index: its node index in the current (possibly contracted) graph.
        var originalToCurrent = IntArray(n) { it }
        val levels = ArrayList<Partition>()

        var level = 0
        while (level < maxLevels) {
            val moved = work.localMoving()
            val communityCount = work.renumber()

            // Nothing merged and nothing moved ⇒ this level would repeat the previous one.
            if (!moved && communityCount == work.size) break

            originalToCurrent = IntArray(n) { i -> work.communityOf[originalToCurrent[i]] }
            val partition = toPartition(graph.nodes, originalToCurrent, communityCount)
            if (levels.isNotEmpty() && levels.last() == partition) break
            levels.add(partition)

            if (communityCount <= 1 || communityCount == work.size) break
            work = work.contract(communityCount, maxSweeps)
            level++
        }

        return levels.ifEmpty { listOf(graph.nodes.map { listOf(it) }) }
    }

    private fun toPartition(paths: List<String>, assignment: IntArray, communityCount: Int): Partition {
        val buckets = Array(communityCount) { ArrayList<String>() }
        for (i in paths.indices) buckets[assignment[i]].add(paths[i])
        return buckets.filter { it.isNotEmpty() }
            .map { it.sorted() }
            // Largest first, then lexicographic by first member: meaningful for a UI list and
            // deterministic for the tests.
            .sortedWith(compareByDescending<List<String>> { it.size }.thenBy { it.first() })
    }

    /**
     * The mutable working graph for one Louvain level. Self-loops are tracked separately: an
     * aggregated node carries its community's internal weight, and a self-loop contributes twice to
     * that node's degree.
     */
    private class Working(
        val size: Int,
        private val neighbours: Array<IntArray>,
        private val weights: Array<DoubleArray>,
        private val selfLoops: DoubleArray,
        private val degrees: DoubleArray,
        private val totalWeight: Double,
        private val maxSweeps: Int,
    ) {
        val communityOf = IntArray(size) { it }
        private val sigmaTot = DoubleArray(size) { degrees[it] }

        /** One full local-moving phase. Returns true if any node changed community. */
        fun localMoving(): Boolean {
            var movedEver = false
            val twoM = 2.0 * totalWeight
            val weightToCommunity = HashMap<Int, Double>()

            repeat(maxSweeps) {
                var movedThisSweep = false
                for (i in 0 until size) {
                    val own = communityOf[i]
                    weightToCommunity.clear()
                    val nbr = neighbours[i]
                    val w = weights[i]
                    for (k in nbr.indices) {
                        val j = nbr[k]
                        if (j == i) continue
                        weightToCommunity.merge(communityOf[j], w[k], Double::plus)
                    }

                    // Isolate i so its own degree does not bias the comparison against its community.
                    sigmaTot[own] -= degrees[i]

                    var bestCommunity = own
                    var bestGain = (weightToCommunity[own] ?: 0.0) - sigmaTot[own] * degrees[i] / twoM
                    for ((c, wIn) in weightToCommunity) {
                        if (c == own) continue
                        val gain = wIn - sigmaTot[c] * degrees[i] / twoM
                        // Strictly better wins; an exact tie goes to the lower community index, so the
                        // result never depends on HashMap iteration order.
                        if (gain > bestGain + EPS || (gain > bestGain - EPS && c < bestCommunity)) {
                            bestGain = gain
                            bestCommunity = c
                        }
                    }

                    sigmaTot[bestCommunity] += degrees[i]
                    if (bestCommunity != own) {
                        communityOf[i] = bestCommunity
                        movedThisSweep = true
                        movedEver = true
                    }
                }
                if (!movedThisSweep) return movedEver
            }
            return movedEver
        }

        /** Compact community ids to `0..count-1` by first appearance, keeping ids deterministic. */
        fun renumber(): Int {
            val remap = HashMap<Int, Int>()
            for (i in 0 until size) {
                remap.getOrPut(communityOf[i]) { remap.size }
            }
            for (i in 0 until size) communityOf[i] = remap.getValue(communityOf[i])
            return remap.size
        }

        /** Build the next level: one node per community, weights summed, internals folded into self-loops. */
        fun contract(communityCount: Int, maxSweeps: Int): Working {
            val agg = Array(communityCount) { HashMap<Int, Double>() }
            val newSelf = DoubleArray(communityCount)

            for (i in 0 until size) {
                val ci = communityOf[i]
                newSelf[ci] += selfLoops[i]
                val nbr = neighbours[i]
                val w = weights[i]
                for (k in nbr.indices) {
                    val j = nbr[k]
                    if (j == i) continue
                    val cj = communityOf[j]
                    // Each undirected edge is seen once from each endpoint; halve it so internal
                    // weight is counted exactly once, matching how selfLoops is defined.
                    if (ci == cj) newSelf[ci] += w[k] / 2.0 else agg[ci].merge(cj, w[k], Double::plus)
                }
            }

            val nbrs = Array(communityCount) { c -> agg[c].keys.toIntArray().sortedArray() }
            val wts = Array(communityCount) { c -> DoubleArray(nbrs[c].size) { k -> agg[c].getValue(nbrs[c][k]) } }
            val degs = DoubleArray(communityCount) { c -> wts[c].sum() + 2.0 * newSelf[c] }
            return Working(communityCount, nbrs, wts, newSelf, degs, degs.sum() / 2.0, maxSweeps)
        }

        companion object {
            private const val EPS = 1e-12

            fun from(graph: NoteGraph, maxSweeps: Int): Working {
                val n = graph.nodes.size
                val nbrs = Array(n) { i -> graph.adjacency[i].map { it.first }.toIntArray() }
                val wts = Array(n) { i -> graph.adjacency[i].map { it.second }.toDoubleArray() }
                return Working(n, nbrs, wts, DoubleArray(n), graph.degrees.copyOf(), graph.totalWeight, maxSweeps)
            }
        }
    }
}
