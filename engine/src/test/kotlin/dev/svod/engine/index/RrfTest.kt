package dev.svod.engine.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RRF must improve ranking over either leg alone: a document that is consistently good in
 * both rankings should beat each leg's single-signal winner. This is the property that
 * makes hybrid retrieval worth more than max(BM25, kNN).
 */
class RrfTest {

    @Test
    fun `doc ranked 2nd in both legs beats each leg's 1 winner`() {
        // BM25 leg: A is #1 (e.g. keyword-stuffed), T is #2.
        val bm25 = listOf("A", "T", "X", "Y")
        // Semantic leg: B is #1 (e.g. topical synonym match), T is #2.
        val semantic = listOf("B", "T", "X", "Y")

        val fused = Rrf.fuse(listOf(bm25, semantic)).keys.toList()

        // T wins: present high in BOTH lists, while A and B are each strong in only one.
        assertEquals("T", fused.first(), "RRF should surface the doubly-relevant doc over each leg's winner")
        assertTrue(fused.indexOf("T") < fused.indexOf("A"), "hybrid improves over the BM25-alone winner A")
        assertTrue(fused.indexOf("T") < fused.indexOf("B"), "hybrid improves over the semantic-alone winner B")
    }

    @Test
    fun `a weighted leg outranks an unweighted one at the same rank`() {
        // Same rank in each leg — only the weight can separate them.
        val keyword = listOf("K")
        val semantic = listOf("S")
        val fused = Rrf.fuse(listOf(keyword, semantic), listOf(1.0, Rrf.DEFAULT_SEMANTIC_WEIGHT)).keys.toList()
        assertEquals("S", fused.first(), "the semantic leg must carry more weight than the keyword leg")
    }

    @Test
    fun `the semantic leg's winner beats a keyword hit buried deep in the semantic leg`() {
        // The exact shape of F2. M is the keyword leg's top hit and sits LAST in the semantic leg's
        // 50-candidate window — the semantic leg considers it barely relevant at all. R is the
        // semantic leg's #1 and the keyword leg never saw it.
        //
        // Equal weighting: M scores 1/61 + 1/110 = 0.0255 against R's 1/61 = 0.0164, so merely being
        // in both lists beats being first in the better one. That is HYBRID scoring below its own
        // semantic leg, in miniature.
        //
        // At weight 3: M scores 1/61 + 3/110 = 0.0437 against R's 3/61 = 0.0492, and R wins.
        //
        // Note how narrow that is, and how far down M had to be. The weight does not let the
        // semantic leg dictate the order — it takes a near-worthless semantic rank before one
        // keyword co-occurrence stops outvoting the semantic leg's best hit. A document ranked
        // second by semantic AND first by keyword still wins, and should.
        val keyword = listOf("M", "K2", "K3")
        val semantic = listOf("R") + List(48) { "S$it" } + listOf("M")

        val equal = Rrf.fuse(listOf(keyword, semantic)).keys.toList()
        assertEquals("M", equal.first(), "the defect this fixes: equal weighting promotes the doubly-listed M")

        val weighted = Rrf.fuse(listOf(keyword, semantic), listOf(1.0, Rrf.DEFAULT_SEMANTIC_WEIGHT)).keys.toList()
        assertEquals("R", weighted.first(), "the semantic leg's top hit must survive a weak keyword co-occurrence")
    }

    @Test
    fun `omitted weights default to 1 so an unweighted call is unchanged`() {
        val a = listOf("A", "T", "X")
        val b = listOf("B", "T", "X")
        assertEquals(
            Rrf.fuse(listOf(a, b)).toList(),
            Rrf.fuse(listOf(a, b), listOf(1.0, 1.0)).toList(),
        )
    }
}
