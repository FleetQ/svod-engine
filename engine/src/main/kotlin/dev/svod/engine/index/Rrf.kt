package dev.svod.engine.index

/**
 * Reciprocal Rank Fusion. Each ranked list contributes `weight / (k + rank)` per item; scores
 * sum across lists. Rank-based (not score-based) fusion is robust to the wildly different
 * score scales of BM25 vs cosine kNN, which is exactly why it's the default here.
 *
 * k=60 is the value from the original Cormack et al. RRF paper and the common default.
 */
object Rrf {
    const val DEFAULT_K = 60

    /**
     * How much the semantic leg outweighs the keyword leg.
     *
     * Textbook RRF weights every list equally, which assumes the legs are comparably good. On a real
     * vault they are not: measured over 45 golden queries on a 3384-note vault, the keyword leg
     * scores nDCG@10 0.18 against the semantic leg's 0.52. Fusing them equally produced the defect
     * recorded as F2 — HYBRID scoring *below its own semantic leg*, i.e. adding a second signal made
     * search worse.
     *
     * Measured at k=60 on the same golden set, on two different first stages:
     *
     * ```
     * weight   bge-m3   e5-small        (nDCG@10; SEMANTIC alone = 0.520 / 0.356)
     *   1.0     0.463     0.311         <- equal weighting: below the semantic leg, both models
     *   2.0     0.542     0.350
     *   3.0     0.552     0.355
     *   5.0     0.547     0.364         <- clears the semantic leg on BOTH
     *  10.0     0.550     0.384
     * ```
     *
     * The curve is flat from 2 to 10 — a broad optimum, not a spike fitted to 45 queries. 3.0 is
     * taken from the LOW end of that plateau rather than either curve's peak, because the risk is
     * asymmetric: this constant assumes the semantic leg is the better one, and where that is false
     * the weight amplifies the damage. The hermetic leg P is the worked example — its embedder is
     * deliberately hashed noise (SEMANTIC nDCG@10 0.37 against KEYWORD's 0.69), and weighting that
     * noise pulls fused quality well below keyword alone. A vault with a failing or badly-matched
     * embedder is the real-world version of the same shape.
     *
     * Keyword still earns its place: dropping it entirely (semantic alone) scores 0.520/0.356,
     * below the weighted fusion. It is a useful tiebreaker here, not a peer.
     *
     * NB: an earlier sweep concluded weighting does nothing. It ran on the 27-note synthetic corpus,
     * where both legs are strong and every relevant note is in the candidate window by construction.
     * The conclusion was measured correctly and about the wrong subject.
     */
    const val DEFAULT_SEMANTIC_WEIGHT = 3.0

    /**
     * @param rankedLists each is an ordered list of item ids, best first.
     * @param weights per-list multiplier, positionally matched to [rankedLists]; missing entries
     *  default to 1.0. See [DEFAULT_SEMANTIC_WEIGHT] for why this is not uniform in practice.
     */
    fun <T> fuse(
        rankedLists: List<List<T>>,
        weights: List<Double> = emptyList(),
        k: Int = DEFAULT_K,
    ): LinkedHashMap<T, Double> {
        val scores = HashMap<T, Double>()
        rankedLists.forEachIndexed { i, list ->
            val w = weights.getOrElse(i) { 1.0 }
            list.forEachIndexed { rank, id ->
                scores[id] = (scores[id] ?: 0.0) + w / (k + rank + 1)
            }
        }
        val ordered = scores.entries.sortedByDescending { it.value }
        return LinkedHashMap<T, Double>().apply { ordered.forEach { put(it.key, it.value) } }
    }
}
