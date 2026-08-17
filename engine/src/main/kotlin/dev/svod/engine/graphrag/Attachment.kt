package dev.svod.engine.graphrag

/**
 * The decision half of incremental attachment: given a new note's vector, which existing community
 * does it belong to?
 *
 * Kept pure and separate from [GraphService] so the rule can be tested without an index, a vault or a
 * background thread — the plumbing around it (when a pass runs, what it persists) is a different
 * concern and fails in different ways.
 *
 * **Why neighbours rather than centroids.** A community centroid is a single point; real communities
 * here are elongated (they were formed by chaining kNN edges, not by k-means), so a note can sit
 * clearly inside one and still be nearer the centroid of another. Voting among the k nearest NOTES
 * reproduces the way the note would have been clustered by Louvain far more closely, and costs the
 * same dot products.
 */
internal object Attachment {

    data class Neighbour(val path: String, val score: Double)

    /**
     * The [k] nearest notes with cosine ≥ [threshold], strongest first.
     *
     * Vectors are L2-normalised by `IndexService.noteVector`, so the dot product IS the cosine.
     * [threshold] is `GraphConfig.effectiveAttachThreshold` — the build's `simThreshold` unless the
     * operator set attachment its own bar. Whatever it is, it is a real floor: below it the note is
     * left pending rather than filed into whichever community happened to be least far away.
     */
    fun nearest(
        target: FloatArray,
        vectors: Map<String, FloatArray>,
        k: Int,
        threshold: Double,
    ): List<Neighbour> {
        if (k <= 0) return emptyList()
        // Min-heap on score: the weakest kept neighbour is the cheapest to evict.
        val best = java.util.PriorityQueue<Neighbour>(k + 1, compareBy { it.score })
        for ((path, v) in vectors) {
            if (v.size != target.size) continue // a mid-flight embedder swap can leave mixed dims
            var dot = 0.0
            for (i in target.indices) dot += target[i] * v[i]
            if (dot < threshold) continue
            if (best.size < k) best.add(Neighbour(path, dot))
            else if (dot > best.peek().score) { best.poll(); best.add(Neighbour(path, dot)) }
        }
        return best.sortedWith(compareByDescending<Neighbour> { it.score }.thenBy { it.path })
    }

    /**
     * The community holding the most of [neighbours], weighted by similarity.
     *
     * Weighted rather than a plain count so a single very close neighbour is not outvoted by three
     * barely-above-threshold ones. Ties break on the community id, so a pass over the same data
     * always produces the same placement. Returns null when no neighbour belongs to any community at
     * this level — the note then stays pending, which is honest, rather than being filed somewhere
     * arbitrary.
     */
    fun dominantCommunity(neighbours: List<Neighbour>, memberOf: Map<String, String>): String? {
        if (neighbours.isEmpty()) return null
        val score = HashMap<String, Double>()
        for (n in neighbours) {
            val c = memberOf[n.path] ?: continue
            score.merge(c, n.score, Double::plus)
        }
        return score.entries
            .maxWithOrNull(compareBy<Map.Entry<String, Double>> { it.value }.thenByDescending { it.key })
            ?.key
    }
}
