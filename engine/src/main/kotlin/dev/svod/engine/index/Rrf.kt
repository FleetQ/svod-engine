package dev.svod.engine.index

/**
 * Reciprocal Rank Fusion. Each ranked list contributes 1/(k + rank) per item; scores
 * sum across lists. Rank-based (not score-based) fusion is robust to the wildly different
 * score scales of BM25 vs cosine kNN, which is exactly why it's the default here.
 *
 * k=60 is the value from the original Cormack et al. RRF paper and the common default.
 */
object Rrf {
    const val DEFAULT_K = 60

    /**
     * @param rankedLists each is an ordered list of item ids, best first.
     *
     * Per-leg weighting was tried and **measured to do nothing** here: weights from 1.0 to 2.0 on
     * the semantic leg left nDCG@10 and recall@5 identical to three decimals, and even 5.0 moved
     * nDCG only 0.786 -> 0.803 while recall@5 never budged. See the fusion sweep in
     * `FusionWeightSweepTest` before adding a weight parameter back.
     */
    fun <T> fuse(rankedLists: List<List<T>>, k: Int = DEFAULT_K): LinkedHashMap<T, Double> {
        val scores = HashMap<T, Double>()
        for (list in rankedLists) {
            list.forEachIndexed { rank, id ->
                scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + rank + 1)
            }
        }
        val ordered = scores.entries.sortedByDescending { it.value }
        return LinkedHashMap<T, Double>().apply { ordered.forEach { put(it.key, it.value) } }
    }
}
