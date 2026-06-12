package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `none` provider must give a fully usable lexical (BM25-only) search with no vectors. */
class EmbedderNoneTest {

    private suspend fun IndexFixture.seedCorpus() {
        seed("vault/cats.md", "---\ntags: [animal]\n---\n# Cats\nCats are feline animals that purr and meow.")
        seed("vault/dogs.md", "---\ntags: [animal]\n---\n# Dogs\nDogs are loyal canine companions that bark.")
        seed("заметки/кошки.md", "---\ntags: [животные]\n---\n# Кошки\nКошки — пушистые домашние животные, которые мурлычут.")
    }

    @Test
    fun `none embedder gives a fully functional lexical search incl Cyrillic`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()
            val idx = fx.newIndex(NoneEmbedder)
            try {
                assertEquals(3, idx.docCount())

                // HYBRID degrades cleanly to lexical when there is no embedder.
                val cats = idx.search(SearchQuery("cats purr", mode = SearchMode.HYBRID))
                assertEquals("vault/cats.md", cats.hits.first().path)
                assertTrue(cats.hits.all { !it.matchedSemantic }, "no semantic matches without an embedder")

                val kw = idx.search(SearchQuery("canine bark", mode = SearchMode.KEYWORD))
                assertEquals("vault/dogs.md", kw.hits.first().path)

                val cyr = idx.search(SearchQuery("кошки мурлычут", mode = SearchMode.KEYWORD))
                assertEquals("заметки/кошки.md", cyr.hits.first().path)

                // Even an explicit SEMANTIC request stays usable (falls back to lexical) — never empty/crash.
                val sem = idx.search(SearchQuery("cats purr", mode = SearchMode.SEMANTIC))
                assertTrue(sem.hits.isNotEmpty(), "semantic request must still return lexical results under none")

                assertEquals("none", idx.indexedModel())
                assertEquals(0, idx.indexedDim())
            } finally { idx.close() }
        }
    }

    @Test
    fun `switching none to active embedder reindexes and adds vectors`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()

            fx.newIndex(NoneEmbedder).use { } // build BM25-only index, then close

            val fake = FakeEmbedder("e5-fake", dim = 64)
            val active = fx.newIndex(fake)
            try {
                assertTrue(fake.passageCalls.get() >= 3, "switching to an active embedder must embed every chunk")
                val sem = active.search(SearchQuery("dogs bark loyal", mode = SearchMode.SEMANTIC))
                assertEquals("vault/dogs.md", sem.hits.first().path)
                assertTrue(sem.hits.first().matchedSemantic)
                assertEquals("e5-fake", active.indexedModel())
                assertEquals(64, active.indexedDim())
            } finally { active.close() }
        }
    }

    @Test
    fun `switching active embedder back to none reindexes lexical-only`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()
            fx.newIndex(FakeEmbedder("e5-fake", dim = 64)).use { }

            val lexical = fx.newIndex(NoneEmbedder)
            try {
                assertEquals("none", lexical.indexedModel())
                assertEquals(3, lexical.docCount())
                val hit = lexical.search(SearchQuery("feline purr", mode = SearchMode.HYBRID)).hits.firstOrNull()
                assertEquals("vault/cats.md", hit?.path)
                assertFalse(hit!!.matchedSemantic)
            } finally { lexical.close() }
        }
    }
}
