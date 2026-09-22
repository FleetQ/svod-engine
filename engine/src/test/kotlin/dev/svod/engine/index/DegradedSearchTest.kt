package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A search that had to go without a retrieval leg says so in [SearchResult.degraded] instead of
 * returning a result that looks complete (test plan D1–D7, `docs/test-plan-evermind-borrow.md`).
 */
class DegradedSearchTest {

    private class FailingReranker : Reranker {
        override val model = "rr-down"
        override val provider = "fake"
        override fun rerank(query: String, docs: List<String>): List<Float> = throw RuntimeException("reranker down")
    }

    private class ConstantReranker : Reranker {
        override val model = "rr-ok"
        override val provider = "fake"
        override fun rerank(query: String, docs: List<String>): List<Float> = docs.map { 0.5f }
    }

    private fun IndexFixture.seedShared() = runBlocking {
        seed("a.md", "---\ntags: [fruit]\n---\n# A\ncommon apple")
        seed("b.md", "# B\ncommon banana")
        seed("c.md", "# C\ncommon cherry")
    }

    private fun IndexFixture.open(embedder: Embedder, reranker: Reranker? = null): IndexService =
        if (reranker == null) IndexService(root, indexDir, embedder, blockStartup = true).start()
        else IndexService(root, indexDir, embedder, blockStartup = true, reranker = reranker).start()

    @Test
    fun `D1 hybrid with a failing query embed reports semantic and still returns keyword hits`() {
        IndexFixture.create().use { fx ->
            fx.seedShared()
            fx.open(QueryFailingEmbedder()).use { idx ->
                val r = idx.search(SearchQuery("apple", mode = SearchMode.HYBRID))
                assertEquals(listOf(SearchResult.SEMANTIC), r.degraded)
                assertEquals("a.md", r.hits.firstOrNull()?.path, "the keyword leg still answers")
            }
        }
    }

    @Test
    fun `D2 semantic mode with a failing query embed reports semantic without throwing`() {
        IndexFixture.create().use { fx ->
            fx.seedShared()
            fx.open(QueryFailingEmbedder()).use { idx ->
                assertEquals(listOf(SearchResult.SEMANTIC), idx.search(SearchQuery("apple", mode = SearchMode.SEMANTIC)).degraded)
            }
        }
    }

    @Test
    fun `D3 keyword mode never reports semantic, even with a failing embedder`() {
        IndexFixture.create().use { fx ->
            fx.seedShared()
            fx.open(QueryFailingEmbedder()).use { idx ->
                assertEquals(emptyList(), idx.search(SearchQuery("apple", mode = SearchMode.KEYWORD)).degraded)
            }
        }
    }

    @Test
    fun `D4 a healthy hybrid search is not degraded`() {
        IndexFixture.create().use { fx ->
            fx.seedShared()
            fx.open(FakeEmbedder("ok")).use { idx ->
                val r = idx.search(SearchQuery("apple", mode = SearchMode.HYBRID))
                assertEquals(emptyList(), r.degraded)
                assertTrue(r.hits.isNotEmpty())
            }
        }
    }

    @Test
    fun `D5 an embedder configured as none is the vault's choice, not a degradation`() {
        IndexFixture.create().use { fx ->
            fx.seedShared()
            fx.open(NoneEmbedder).use { idx ->
                assertEquals(emptyList(), idx.search(SearchQuery("apple", mode = SearchMode.HYBRID)).degraded)
            }
        }
    }

    @Test
    fun `D6 a failing reranker reports rerank and keeps the fused order`() {
        IndexFixture.create().use { fx ->
            fx.seedShared()
            val fused = fx.open(FakeEmbedder("ok")).use { idx ->
                idx.search(SearchQuery("common", mode = SearchMode.KEYWORD, limit = 3)).hits.map { it.path }
            }
            fx.open(FakeEmbedder("ok"), FailingReranker()).use { idx ->
                val r = idx.search(SearchQuery("common", mode = SearchMode.KEYWORD, limit = 3))
                assertEquals(listOf(SearchResult.RERANK), r.degraded)
                assertEquals(fused, r.hits.map { it.path }, "a failed rerank leaves the fused order")
            }
            fx.open(FakeEmbedder("ok"), ConstantReranker()).use { idx ->
                assertEquals(emptyList(), idx.search(SearchQuery("common", mode = SearchMode.KEYWORD, limit = 3)).degraded)
            }
        }
    }

    @Test
    fun `D7 a filter-only browse has no query to embed and is not degraded`() {
        IndexFixture.create().use { fx ->
            fx.seedShared()
            fx.open(QueryFailingEmbedder()).use { idx ->
                val r = idx.search(SearchQuery("", SearchFilters(tags = listOf("fruit")), SearchMode.HYBRID))
                assertEquals(emptyList(), r.degraded)
                assertEquals(listOf("a.md"), r.hits.map { it.path })
            }
        }
    }

    @Test
    fun `hybrid with both a failing embed and a failing reranker reports both, semantic first`() {
        IndexFixture.create().use { fx ->
            fx.seedShared()
            fx.open(QueryFailingEmbedder(), FailingReranker()).use { idx ->
                assertEquals(listOf(SearchResult.SEMANTIC, SearchResult.RERANK),
                    idx.search(SearchQuery("common", mode = SearchMode.HYBRID)).degraded)
            }
        }
    }
}
