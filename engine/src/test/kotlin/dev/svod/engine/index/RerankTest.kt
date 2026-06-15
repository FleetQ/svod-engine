package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The opt-in second-stage reranker: it reorders the fused candidates, and degrades to fused on failure. */
class RerankTest {

    /** Scores a doc 1.0 if it contains [boost], else 0.0 — a deterministic stand-in for a cross-encoder. */
    private class FakeReranker(private val boost: String) : Reranker {
        override val model = "fake-rr"
        override val provider = "fake"
        val calls = AtomicInteger(0)
        override fun rerank(query: String, docs: List<String>): List<Float> {
            calls.incrementAndGet()
            return docs.map { if (it.contains(boost, ignoreCase = true)) 1f else 0f }
        }
    }

    private class FailingReranker : Reranker {
        override val model = "rr-cold"
        override val provider = "fake"
        override fun rerank(query: String, docs: List<String>): List<Float> = throw RuntimeException("reranker down")
    }

    private fun IndexFixture.seedShared() = runBlocking {
        seed("a.md", "# A\ncommon apple")
        seed("b.md", "# B\ncommon banana")
        seed("c.md", "# C\ncommon cherry")
    }

    @Test
    fun `an active reranker reorders the fused candidates`() {
        IndexFixture.create().use { fx ->
            fx.seedShared()
            // Baseline (no reranker): c.md is not guaranteed first.
            IndexService(fx.root, fx.indexDir, FakeEmbedder("f"), blockStartup = true,
                reranker = FakeReranker("cherry")).start().use { idx ->
                val hits = idx.search(SearchQuery("common", mode = SearchMode.KEYWORD, limit = 3)).hits
                assertEquals(3, hits.size, "all three share the query term")
                assertEquals("c.md", hits.first().path, "the reranker boosts the cherry doc to the top")
            }
        }
    }

    @Test
    fun `a failing reranker degrades to the fused order, never errors the search`() {
        IndexFixture.create().use { fx ->
            fx.seedShared()
            IndexService(fx.root, fx.indexDir, FakeEmbedder("f"), blockStartup = true,
                reranker = FailingReranker()).start().use { idx ->
                val hits = idx.search(SearchQuery("common", mode = SearchMode.KEYWORD, limit = 3)).hits
                assertEquals(3, hits.size, "search still returns the fused results despite the reranker failing")
            }
        }
    }

    @Test
    fun `no reranker (default) leaves results untouched`() {
        IndexFixture.create().use { fx ->
            fx.seedShared()
            IndexService(fx.root, fx.indexDir, FakeEmbedder("f"), blockStartup = true).start().use { idx ->
                val hits = idx.search(SearchQuery("common", mode = SearchMode.KEYWORD, limit = 3)).hits
                assertTrue(hits.isNotEmpty())
            }
        }
    }
}
