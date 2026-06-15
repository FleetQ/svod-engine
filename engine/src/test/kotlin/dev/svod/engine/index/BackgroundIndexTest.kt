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

    /**
     * Like [FakeEmbedder] but with a LAZY dimension: [knownDim] is 0 until the first successful embed.
     * This mirrors the real remote/Ollama embedders, whose dimension is unknown on a cold process —
     * the case the plain [FakeEmbedder] (eager dim) didn't cover for restart-resume.
     */
    private class LazyDimEmbedder(m: String, private val d: Int = 64) : Embedder {
        private val inner = FakeEmbedder(m, d)
        val passageCalls get() = inner.passageCalls
        @Volatile private var cached = 0
        override val model = m
        override val isActive = true
        override val dim: Int get() { if (cached == 0) cached = d; return cached }
        override fun knownDim() = cached
        override fun embedPassages(texts: List<String>): List<FloatArray> { cached = d; return inner.embedPassages(texts) }
        override fun embedQuery(text: String) = inner.embedQuery(text)
    }

    @Test
    fun `a lazy-dimension (remote) embedder resumes across restart with zero re-embed`() {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()
            val first = LazyDimEmbedder("remote-bge")
            IndexService(fx.root, fx.indexDir, first, blockStartup = true).start().use { idx ->
                assertEquals(3, first.passageCalls.get(), "first run embeds all chunks")
                assertEquals(64, idx.indexedDim(), "the real dimension is recorded after the first embed")
            }
            // Reopen with a FRESH lazy embedder (knownDim()==0, like a cold remote). The dim-0 wildcard
            // in IndexMeta.compatibleWith must let it resume against the existing index — NOT wipe + re-embed.
            val second = LazyDimEmbedder("remote-bge")
            IndexService(fx.root, fx.indexDir, second, blockStartup = true).start().use { idx ->
                assertEquals(0, second.passageCalls.get(), "cold remote restart must reuse persisted vectors, not re-embed")
                assertEquals("c.md", idx.search(SearchQuery("cherry", mode = SearchMode.SEMANTIC)).hits.firstOrNull()?.path)
            }
        }
    }

    /** Embeds the first [n] calls, then throws — simulating an interruption partway through the backlog. */
    private class FailAfterNEmbedder(override val model: String, private val n: Int, override val dim: Int = 64) : Embedder {
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        override val isActive = true
        override fun knownDim() = dim
        override fun embedPassages(texts: List<String>): List<FloatArray> {
            if (attempts.incrementAndGet() > n) throw RuntimeException("simulated interruption")
            return texts.map { FloatArray(dim).also { it[0] = 1f } }
        }
        override fun embedQuery(text: String) = FloatArray(dim).also { it[0] = 1f }
    }

    @Test
    fun `an interrupted embedding pass resumes only the remaining files on restart`() {
        IndexFixture.create().use { fx ->
            runBlocking { for (i in 1..6) fx.seed("n$i.md", "# N$i\nbody $i words") }
            // batchSize=1 + maxThreads=1 ⇒ one file per embed call, committed in order. The embedder
            // dies after 3 → exactly 3 files end up with vectors, the pass lands in ERROR.
            val dying = FailAfterNEmbedder("fake-v1", n = 3)
            IndexService(fx.root, fx.indexDir, dying, blockStartup = true, maxThreads = 1, batchSize = 1).start().use { idx ->
                assertEquals(IndexService.EmbeddingState.ERROR, idx.embeddingStatus().state)
            }
            // Restart with a healthy embedder: only the 3 files still missing vectors are embedded.
            val healthy = FakeEmbedder("fake-v1")
            IndexService(fx.root, fx.indexDir, healthy, blockStartup = true, maxThreads = 1, batchSize = 1).start().use {
                assertEquals(3, healthy.passageCalls.get(), "resume embeds only the 3 unfinished files, not all 6")
            }
        }
    }

    /** Embeds normally, but throws on any text containing "POISON" — a stand-in for a model rejecting one oversized chunk. */
    private class SelectiveFailEmbedder(m: String = "sel") : Embedder {
        private val inner = FakeEmbedder(m)
        val embedded get() = inner.passageCalls
        override val model = m
        override val dim = 64
        override val isActive = true
        override fun knownDim() = dim
        override fun embedPassages(texts: List<String>): List<FloatArray> {
            if (texts.any { it.contains("POISON") }) throw RuntimeException("input length exceeds the context length")
            return inner.embedPassages(texts)
        }
        override fun embedQuery(text: String) = inner.embedQuery(text)
    }

    @Test
    fun `one chunk that fails to embed is skipped and the pass still completes`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                fx.seed("a.md", "# A\nclean alpha")
                fx.seed("bad.md", "# Bad\nPOISON oversized content")
                fx.seed("c.md", "# C\nclean gamma")
            }
            val emb = SelectiveFailEmbedder()
            // batchSize 64 ⇒ one group; the batch fails on the poison chunk, individual retry skips it.
            IndexService(fx.root, fx.indexDir, emb, blockStartup = true, maxThreads = 1, batchSize = 64).start().use { idx ->
                val st = idx.embeddingStatus()
                assertEquals(IndexService.EmbeddingState.IDLE, st.state, "pass completes despite one bad chunk")
                assertEquals(st.total, st.done, "progress reaches 100%")
                assertEquals(2, emb.embedded.get(), "the two clean chunks embed; the poison one is skipped")
                // the clean docs are semantically searchable; the skipped one simply has no vector
                assertEquals("a.md", idx.search(SearchQuery("alpha", mode = SearchMode.SEMANTIC)).hits.firstOrNull()?.path)
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
