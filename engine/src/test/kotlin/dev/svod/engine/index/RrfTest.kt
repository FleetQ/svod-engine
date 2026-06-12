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
}
