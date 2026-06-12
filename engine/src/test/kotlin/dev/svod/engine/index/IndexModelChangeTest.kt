package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexModelChangeTest {

    private suspend fun IndexFixture.seed2() {
        seed("one.md", "# One\nphotosynthesis converts light to energy")
        seed("two.md", "# Two\nmitochondria are the powerhouse of the cell")
    }

    @Test
    fun `changing the embedding model triggers a full reindex`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seed2()

            val modelA = FakeEmbedder("model-A", dim = 64)
            val totalChunks: Int
            val idxA = fx.newIndex(modelA)
            try {
                totalChunks = modelA.passageCalls.get()
                assertTrue(totalChunks >= 2)
                assertEquals(totalChunks, idxA.docCount())
            } finally { idxA.close() }

            // same dimensions, different model name → must reindex (vectors are not comparable)
            val modelB = FakeEmbedder("model-B", dim = 64)
            val idxB = fx.newIndex(modelB)
            try {
                assertEquals(totalChunks, modelB.passageCalls.get(), "model change must re-embed every chunk")
                assertEquals(totalChunks, idxB.docCount())
                assertTrue(idxB.search(SearchQuery("mitochondria", mode = SearchMode.SEMANTIC)).hits.isNotEmpty())
                assertEquals(fx.engine.head(), idxB.headCommitIndexed())
            } finally { idxB.close() }
        }
    }

    @Test
    fun `changing the embedding dimension reindexes without corruption`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seed2()

            val idx64 = fx.newIndex(FakeEmbedder("model-A", dim = 64))
            val total: Int
            try { total = idx64.docCount() } finally { idx64.close() }

            val model32 = FakeEmbedder("model-A", dim = 32) // dim mismatch vs stored vectors
            val idx32 = fx.newIndex(model32)
            try {
                assertEquals(total, model32.passageCalls.get(), "dim change must re-embed all chunks")
                assertEquals(total, idx32.docCount())
                val hit = idx32.search(SearchQuery("photosynthesis light", mode = SearchMode.HYBRID)).hits.firstOrNull()
                assertEquals("one.md", hit?.path)
            } finally { idx32.close() }
        }
    }
}
