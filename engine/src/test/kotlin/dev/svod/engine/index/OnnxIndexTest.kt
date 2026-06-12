package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end index behaviour with the real in-process e5 embedder. */
class OnnxIndexTest {

    private fun e5OrSkip(): OnnxConfig {
        val cfg = cachedE5ConfigOrNull()
        assumeTrue(cfg != null, "e5-small model not cached — skipping ONNX index test")
        return cfg!!
    }

    @Test
    fun `real e5 hybrid search finds the right doc incl Cyrillic`() = runBlocking {
        val cfg = e5OrSkip()
        IndexFixture.create().use { fx ->
            fx.seed("vault/cats.md", "# Cats\nCats are feline animals that purr and chase mice.")
            fx.seed("vault/cars.md", "# Cars\nAutomobiles are wheeled motor vehicles for road transport.")
            fx.seed("заметки/кошки.md", "# Кошки\nКот — маленькое домашнее животное, которое ловит мышей.")

            val emb = OnnxLocalEmbedder.load(cfg, Path.of("/unused"))
            try {
                val idx = fx.newIndex(emb)
                try {
                    // synonym not present in any document
                    val kitten = idx.search(SearchQuery("kitten", mode = SearchMode.SEMANTIC))
                    assertEquals("vault/cats.md", kitten.hits.first().path)

                    val hybrid = idx.search(SearchQuery("feline that purrs", mode = SearchMode.HYBRID))
                    assertEquals("vault/cats.md", hybrid.hits.first().path)

                    val ru = idx.search(SearchQuery("кошка ловит мышей", mode = SearchMode.HYBRID))
                    assertEquals("заметки/кошки.md", ru.hits.first().path)
                } finally { idx.close() }
            } finally { emb.close() }
        }
    }

    @Test
    fun `provider swap via factory - onnx, none, ollama types and onnx to none reindex`() = runBlocking {
        val cfg = e5OrSkip()
        // factory returns the right provider types (OLLAMA is covered by OllamaIntegrationTest,
        // since constructing it probes a live server).
        assertTrue(Embedders.create(EmbedderConfig(EmbedderProvider.NONE), Path.of("/x")) is NoneEmbedder)
        val onnx = Embedders.create(EmbedderConfig(EmbedderProvider.ONNX_LOCAL, onnx = cfg), Path.of("/x"))
        try {
            assertTrue(onnx is OnnxLocalEmbedder)
            assertEquals(384, onnx.dim)
        } finally { (onnx as AutoCloseable).close() }

        // onnx-local -> none reindex leaves a usable lexical index
        IndexFixture.create().use { fx ->
            fx.seed("vault/note.md", "# Note\nphotosynthesis converts sunlight into chemical energy")
            val emb = OnnxLocalEmbedder.load(cfg, Path.of("/unused"))
            try {
                fx.newIndex(emb).use { onnxIdx -> assertEquals(384, onnxIdx.indexedDim()) }
            } finally { emb.close() }

            fx.newIndex(NoneEmbedder).use { noneIdx ->
                assertEquals(0, noneIdx.indexedDim())
                assertEquals("vault/note.md", noneIdx.search(SearchQuery("photosynthesis sunlight", mode = SearchMode.HYBRID)).hits.first().path)
            }
        }
    }
}
