package dev.svod.engine.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the IR metrics themselves ([RetrievalEval.recallAt], [RetrievalEval.ndcgAt],
 * [RetrievalEval.reciprocalRank], [RetrievalEval.rankedPaths]) — these are the measuring
 * instrument for retrieval quality, so a bug here would silently misreport every eval run.
 * Pure math on hand-built [GoldenQuery]/ranking literals: no index, no engine, no I/O.
 */
class RetrievalMetricsTest {

    @Test
    fun `perfect ranking scores 1_0 across all metrics`() {
        val q = GoldenQuery("q", gains = mapOf("a" to 3, "b" to 1))
        val ranked = listOf("a", "b", "irrelevant")

        assertEquals(1.0, RetrievalEval.recallAt(ranked, q, 2))
        assertEquals(1.0, RetrievalEval.ndcgAt(ranked, q, 10))
        assertEquals(1.0, RetrievalEval.reciprocalRank(ranked, q))
    }

    @Test
    fun `nothing relevant retrieved scores zero`() {
        val q = GoldenQuery("q", gains = mapOf("a" to 2))
        val ranked = listOf("x", "y", "z")

        assertEquals(0.0, RetrievalEval.recallAt(ranked, q, 10))
        assertEquals(0.0, RetrievalEval.ndcgAt(ranked, q, 10))
        assertEquals(0.0, RetrievalEval.reciprocalRank(ranked, q))
    }

    @Test
    fun `first relevant at rank 3 gives reciprocal rank of 1_3`() {
        val q = GoldenQuery("q", gains = mapOf("a" to 2))
        val ranked = listOf("x", "y", "a")

        assertEquals(1.0 / 3.0, RetrievalEval.reciprocalRank(ranked, q), 1e-9)
    }

    @Test
    fun `graded ordering rewards the higher-gain doc first`() {
        val q = GoldenQuery("q", gains = mapOf("a" to 3, "b" to 1))

        val gain3First = RetrievalEval.ndcgAt(listOf("a", "b"), q, 10)
        val gain1First = RetrievalEval.ndcgAt(listOf("b", "a"), q, 10)

        assertTrue(gain3First > gain1First, "putting the gain-3 doc first must score strictly higher nDCG")
    }

    @Test
    fun `k truncation excludes a relevant doc past the cutoff`() {
        val q = GoldenQuery("q", gains = mapOf("a" to 1))
        // "a" sits at rank 7 (index 6): six irrelevant docs ahead of it.
        val ranked = listOf("i1", "i2", "i3", "i4", "i5", "i6", "a")

        assertEquals(0.0, RetrievalEval.recallAt(ranked, q, 5))
        assertTrue(RetrievalEval.recallAt(ranked, q, 10) > 0.0)
    }

    @Test
    fun `empty ranked list yields all zero metrics without exception`() {
        val q = GoldenQuery("q", gains = mapOf("a" to 2))
        val ranked = emptyList<String>()

        assertEquals(0.0, RetrievalEval.recallAt(ranked, q, 10))
        assertEquals(0.0, RetrievalEval.ndcgAt(ranked, q, 10))
        assertEquals(0.0, RetrievalEval.reciprocalRank(ranked, q))
    }

    @Test
    fun `all gains zero is handled without divide-by-zero`() {
        val q = GoldenQuery("q", gains = mapOf("a" to 0, "b" to 0))
        val ranked = listOf("a", "b")

        assertEquals(0.0, RetrievalEval.recallAt(ranked, q, 10))
        assertEquals(0.0, RetrievalEval.ndcgAt(ranked, q, 10))
        assertEquals(0.0, RetrievalEval.reciprocalRank(ranked, q))
    }

    @Test
    fun `exact nDCG value for a reversed graded ranking`() {
        // Two docs, gains 3 and 1, retrieved gain-1-first (the reverse of ideal order).
        //
        // dcg(gains) = sum_i (2^gain_i - 1) / log2(i + 2), i 0-based.
        //
        // Ideal order [3, 1]:
        //   i=0: (2^3 - 1) / log2(2) = 7 / 1                = 7.0
        //   i=1: (2^1 - 1) / log2(3) = 1 / 1.5849625007211562 = 0.6309297535714574
        //   idcg = 7.630929753571457
        //
        // Retrieved order [1, 3] (reversed):
        //   i=0: (2^1 - 1) / log2(2) = 1 / 1                = 1.0
        //   i=1: (2^3 - 1) / log2(3) = 7 / 1.5849625007211562 = 4.416508275000202
        //   dcg  = 5.416508275000202
        //
        // ndcg = 5.416508275000202 / 7.630929753571457 = 0.7098097413968655
        val q = GoldenQuery("q", gains = mapOf("hi" to 3, "lo" to 1))
        val reversed = listOf("lo", "hi")

        assertEquals(0.7098097413968655, RetrievalEval.ndcgAt(reversed, q, 10), 1e-9)
    }

    @Test
    fun `rankedPaths collapses duplicate paths to first occurrence`() {
        fun hit(chunkId: String, path: String) = SearchHit(
            chunkId = chunkId,
            path = path,
            heading = "h",
            snippet = "s",
            tags = emptyList(),
            score = 1.0,
            matchedKeyword = true,
            matchedSemantic = false,
        )
        val hits = listOf(
            hit("c1", "p1"),
            hit("c2", "p2"),
            hit("c3", "p1"),
        )

        assertEquals(listOf("p1", "p2"), RetrievalEval.rankedPaths(hits))
    }
}
