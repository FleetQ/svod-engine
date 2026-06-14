package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The non-blocking, keyword-first, resumable startup path (STEP 1). Uses [FakeEmbedder] so the
 * timing is deterministic without any model download.
 */
class BackgroundIndexTest {

    private fun IndexFixture.seedCorpus() = runBlocking {
        seed("a.md", "# Alpha\napple apple apple")
        seed("b.md", "# Beta\nbanana banana banana")
        seed("c.md", "# Gamma\ncherry cherry cherry")
    }

    private fun await(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        error("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun `non-blocking start makes keyword search available before embeddings finish`() {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()
            // A slow embedder so the background pass is still running right after start() returns.
            val emb = FakeEmbedder("fake-v1", delayMs = 150)
            val idx = IndexService(fx.root, fx.indexDir, emb, blockStartup = false, maxThreads = 2, batchSize = 1)
            try {
                idx.pause() // hold the embedding pass (before start) so we observe the keyword-first window
                idx.start()
                await { idx.keywordReady() }
                await { idx.embeddingStatus().state == IndexService.EmbeddingState.PAUSED }

                // BM25 works immediately, with NO embeddings done yet.
                val kw = idx.search(SearchQuery("banana", mode = SearchMode.KEYWORD))
                assertEquals("b.md", kw.hits.firstOrNull()?.path)
                assertEquals(0, emb.passageCalls.get(), "no chunk embedded while paused")
                assertTrue(idx.embeddingStatus().total >= 3, "backlog counted")

                // Semantic returns nothing until vectors exist (never serves empty/stale as keyword).
                assertTrue(idx.search(SearchQuery("banana", mode = SearchMode.SEMANTIC)).hits.isEmpty())

                // Resume → the backlog drains and semantic comes online.
                idx.resume()
                await { idx.embeddingStatus().let { it.state == IndexService.EmbeddingState.IDLE && it.done == it.total } }
                assertEquals(3, emb.passageCalls.get(), "all three chunks embedded once")
                assertEquals("b.md", idx.search(SearchQuery("banana", mode = SearchMode.SEMANTIC)).hits.firstOrNull()?.path)
            } finally { idx.close() }
        }
    }

    @Test
    fun `embeddings persist across a restart - resume re-embeds nothing`() {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()
            val first = FakeEmbedder("fake-v1")
            IndexService(fx.root, fx.indexDir, first, blockStartup = true).start().use {
                // blockStartup waits through the embedding pass, so it is already complete here.
            }
            assertEquals(3, first.passageCalls.get(), "first run embeds all chunks")

            // Reopen with the SAME model/dim: every vector is already on disk → zero re-embedding.
            val second = FakeEmbedder("fake-v1")
            IndexService(fx.root, fx.indexDir, second, blockStartup = true).start().use { idx ->
                assertEquals(0, second.passageCalls.get(), "resume must reuse persisted vectors")
                assertEquals("c.md", idx.search(SearchQuery("cherry", mode = SearchMode.SEMANTIC)).hits.firstOrNull()?.path)
            }
        }
    }
}
