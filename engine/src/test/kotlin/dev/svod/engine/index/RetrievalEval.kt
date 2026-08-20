package dev.svod.engine.index

import kotlin.math.ln
import kotlin.math.pow

/**
 * Retrieval-quality measurement for the search stack: a golden set of queries with graded
 * relevance, scored with the standard IR metrics.
 *
 * This exists because the rest of the suite proves the search *mechanics* work (both legs fire,
 * RRF fuses correctly) and none of it can fail when results simply get worse. Anything that
 * changes ranking — chunking, fusion, a reranker, a different embedder — is graded here.
 *
 * Test scope on purpose: evaluating retrieval is a development concern, not a product surface.
 */

/**
 * One golden query. [gains] maps a note **path** to graded relevance 0..3 (3 = the note that
 * answers the question, 1 = related and acceptable in the list). Paths absent from the map score 0.
 * [why] documents the retrieval capability the query is here to exercise, so a future reader can
 * tell a deliberate hard case from an accident.
 */
data class GoldenQuery(
    val text: String,
    val gains: Map<String, Int>,
    val why: String = "",
    /**
     * Reporting bucket (e.g. a language direction). An aggregate is exactly what hid the
     * cross-lingual collapse on the synthetic corpus until the subsets were reported separately —
     * a golden set that knows its own groups makes that failure harder to repeat.
     */
    val group: String = "",
) {
    val relevant: Set<String> get() = gains.filterValues { it > 0 }.keys
}

/** Macro-averaged metrics over a golden set (each query weighs the same, regardless of hit count). */
data class EvalMetrics(
    val queries: Int,
    val recallAt1: Double,
    val recallAt5: Double,
    val recallAt10: Double,
    val ndcgAt10: Double,
    val mrr: Double,
) {
    fun format(label: String): String = "%-28s n=%-3d R@1=%.3f R@5=%.3f R@10=%.3f nDCG@10=%.3f MRR=%.3f"
        .format(label, queries, recallAt1, recallAt5, recallAt10, ndcgAt10, mrr)
}

object RetrievalEval {

    /**
     * Chunk hits collapsed to note paths, first occurrence wins.
     *
     * Search returns chunk-level hits and one note can contribute several. Golden labels are
     * per note ("did search surface the right note"), so scoring chunk-level would penalise a
     * correct note for having its best chunk second, and would let one note eat several slots at k.
     */
    fun rankedPaths(hits: List<SearchHit>): List<String> = hits.map { it.path }.distinct()

    /** Fraction of the relevant notes that appear in the top [k]. */
    fun recallAt(ranked: List<String>, q: GoldenQuery, k: Int): Double {
        val relevant = q.relevant
        if (relevant.isEmpty()) return 0.0
        val found = ranked.take(k).count { it in relevant }
        return found.toDouble() / relevant.size
    }

    /**
     * Normalised discounted cumulative gain with the standard exponential gain `2^g - 1` and a
     * `log2(rank + 1)` discount. Exponential gain is what makes a grade-3 hit worth materially
     * more than a grade-1 — the reason for grading labels instead of marking them binary.
     */
    fun ndcgAt(ranked: List<String>, q: GoldenQuery, k: Int): Double {
        val idcg = dcg(q.gains.values.sortedDescending().take(k))
        if (idcg == 0.0) return 0.0
        return dcg(ranked.take(k).map { q.gains[it] ?: 0 }) / idcg
    }

    /** 1 / rank of the first relevant note, 0 when the top [k] holds none. */
    fun reciprocalRank(ranked: List<String>, q: GoldenQuery, k: Int = Int.MAX_VALUE): Double {
        val idx = ranked.take(k).indexOfFirst { it in q.relevant }
        return if (idx < 0) 0.0 else 1.0 / (idx + 1)
    }

    private fun dcg(gains: List<Int>): Double =
        gains.withIndex().sumOf { (i, g) -> (2.0.pow(g) - 1.0) / (ln((i + 2).toDouble()) / ln(2.0)) }

    /**
     * Runs [queries] against [index] in [mode] and macro-averages the metrics.
     *
     * [onQuery] receives every per-query result so a caller can print the detail that explains a
     * bad average — an aggregate alone never tells you *which* query broke.
     */
    fun run(
        index: IndexService,
        queries: List<GoldenQuery>,
        mode: SearchMode,
        limit: Int = 10,
        onQuery: (GoldenQuery, List<String>) -> Unit = { _, _ -> },
    ): EvalMetrics {
        require(queries.isNotEmpty()) { "golden set is empty" }
        queries.forEach {
            require(it.relevant.isNotEmpty()) { "golden query has no relevant note: '${it.text}'" }
        }
        var r1 = 0.0; var r5 = 0.0; var r10 = 0.0; var nd = 0.0; var rr = 0.0
        for (q in queries) {
            val ranked = rankedPaths(index.search(SearchQuery(q.text, mode = mode, limit = limit)).hits)
            onQuery(q, ranked)
            r1 += recallAt(ranked, q, 1)
            r5 += recallAt(ranked, q, 5)
            r10 += recallAt(ranked, q, 10)
            nd += ndcgAt(ranked, q, 10)
            rr += reciprocalRank(ranked, q)
        }
        val n = queries.size
        return EvalMetrics(n, r1 / n, r5 / n, r10 / n, nd / n, rr / n)
    }

    /** Per-query line used when a floor fails — shows what was expected and what came back. */
    fun explain(q: GoldenQuery, ranked: List<String>): String =
        "  q='${q.text}' want=${q.relevant} got=${ranked.take(5)}"
}
