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

    /** Constructs with no network (like the fixed remote embedders) but fails every embed call. */
    private class FailingEmbedder(override val model: String = "remote-cold") : Embedder {
        override val dim: Int get() = throw RuntimeException("endpoint cold")
        override val isActive = true
        override fun knownDim() = 0
        override fun embedPassages(texts: List<String>): List<FloatArray> = throw RuntimeException("endpoint cold")
        override fun embedQuery(text: String): FloatArray = throw RuntimeException("endpoint cold")
    }

    @Test
    fun `a failing embedder never breaks boot - keyword works, embedding goes to error`() {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()
            val idx = IndexService(fx.root, fx.indexDir, FailingEmbedder(), blockStartup = false, maxThreads = 2, batchSize = 64)
            try {
                idx.start() // must NOT throw, even though the embedder fails
                await { idx.keywordReady() }
                // BM25 is fully available despite the embedder being down.
                assertEquals("b.md", idx.search(SearchQuery("banana", mode = SearchMode.KEYWORD)).hits.firstOrNull()?.path)
                // the embedding pass ends in error (with the message), not a crash
                await { idx.embeddingStatus().state == IndexService.EmbeddingState.ERROR }
                assertTrue(idx.embeddingStatus().error?.contains("cold") == true, idx.embeddingStatus().error)
                // semantic is suppressed/empty, never throws to the caller
                assertTrue(idx.search(SearchQuery("banana", mode = SearchMode.SEMANTIC)).hits.isEmpty())
            } finally { idx.close() }
        }
    }

    @Test
    fun `embedding batches across files into one call`() {
        IndexFixture.create().use { fx ->
            runBlocking { for (i in 1..10) fx.seed("n$i.md", "# N$i\nbody $i unique words") }
            val emb = FakeEmbedder("fake-v1")
            // batchSize 64 ≫ 10 single-chunk files ⇒ all chunks should go in ONE embedPassages call.
            IndexService(fx.root, fx.indexDir, emb, blockStartup = true, maxThreads = 1, batchSize = 64).start().use {
                assertEquals(10, emb.passageCalls.get(), "all 10 chunks embedded")
                assertEquals(1, emb.batchSizes.size, "cross-file batching ⇒ a single batched call, got ${emb.batchSizes}")
                assertEquals(10, emb.batchSizes.first(), "the one batch carried all 10 docs")
            }
        }
    }

    @Test
    fun `a dimension change re-embeds the whole vault`() {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()
            IndexService(fx.root, fx.indexDir, FakeEmbedder("fake-v1", dim = 64), blockStartup = true).start().use { idx ->
                assertEquals(64, idx.indexedDim())
            }
            // Reopen with a different dimension: the vector field can't be mixed, so boot detects the
            // mismatch, wipes, and rebuilds keyword-first + background.
            val bigger = FakeEmbedder("fake-v1", dim = 128)
            IndexService(fx.root, fx.indexDir, bigger, blockStartup = true).start().use { idx ->
                assertEquals(3, bigger.passageCalls.get(), "every chunk re-embedded at the new dimension")
                assertEquals(128, idx.indexedDim())
                assertEquals("b.md", idx.search(SearchQuery("banana", mode = SearchMode.SEMANTIC)).hits.firstOrNull()?.path)
            }
        }
    }
}
