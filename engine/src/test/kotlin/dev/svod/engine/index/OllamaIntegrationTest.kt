package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-embedding semantic retrieval against a live Ollama (multilingual-e5-large).
 * Skipped automatically when Ollama is unreachable, so the suite stays hermetic in CI.
 * Proves the thing a fake embedder cannot: matching by meaning, not shared tokens.
 */
class OllamaIntegrationTest {

    @Test
    fun `semantic search matches synonyms not present in the text`() = runBlocking {
        assumeTrue(OllamaEmbedder.isAvailable(), "Ollama not available — skipping real-embedding test")

        IndexFixture.create().use { fx ->
            fx.seed("vault/feline.md", "# Felines\nA cat is a small domesticated carnivore that purrs and chases mice.")
            fx.seed("vault/auto.md", "# Automobiles\nAn automobile is a wheeled motor vehicle used for road transportation.")
            fx.seed("vault/кошки.md", "# Кошки\nКот — это маленькое домашнее животное, которое ловит мышей.")

            val idx = fx.newIndex(OllamaEmbedder())
            try {
                // "kitten" appears in no document, yet should retrieve the cat note semantically.
                val kitten = idx.search(SearchQuery("kitten", mode = SearchMode.SEMANTIC))
                assertEquals("vault/feline.md", kitten.hits.first().path, "semantic search should map kitten→cat")

                // "sedan" → automobile note.
                val sedan = idx.search(SearchQuery("sedan vehicle", mode = SearchMode.SEMANTIC))
                assertEquals("vault/auto.md", sedan.hits.first().path)

                // Cross-lingual: Russian synonym retrieves the Russian cat note.
                val ru = idx.search(SearchQuery("котёнок", mode = SearchMode.SEMANTIC))
                assertTrue(ru.hits.any { it.path == "vault/кошки.md" }, "Russian query should retrieve the Russian note: ${ru.hits.map { it.path }}")

                // Hybrid still works end-to-end with real vectors.
                val hybrid = idx.search(SearchQuery("cat mice", mode = SearchMode.HYBRID))
                assertEquals("vault/feline.md", hybrid.hits.first().path)
            } finally { idx.close() }
        }
    }
}
