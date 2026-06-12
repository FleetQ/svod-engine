package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexIncrementalTest {

    private val THREE_SECTIONS = """
        # Alpha
        apple apple apple
        # Beta
        banana banana banana
        # Gamma
        cherry cherry cherry
    """.trimIndent()

    @Test
    fun `editing one section re-embeds only that chunk`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seed("doc.md", THREE_SECTIONS)
            val fake = FakeEmbedder("fake-v1")
            val idx = fx.newIndex(fake)
            try {
                fx.engine.onCommit { idx.onCommit(it) }
                assertEquals(3, fake.passageCalls.get(), "initial index embeds all 3 chunks")

                // change only the Beta section; Alpha and Gamma are byte-identical
                val rev = fx.engine.read("doc.md")!!.revision
                val edited = THREE_SECTIONS.replace("banana banana banana", "blueberry blueberry blueberry")
                fx.engine.write("doc.md", edited, expectedRevision = rev, author = INDEXER)
                idx.waitIdle()

                assertEquals(4, fake.passageCalls.get(), "only the changed chunk should be re-embedded (3 + 1)")

                // the index now reflects the new content, not the old
                val hitNew = idx.search(SearchQuery("blueberry", mode = SearchMode.KEYWORD)).hits.firstOrNull()
                assertEquals("doc.md", hitNew?.path)
                assertTrue(idx.search(SearchQuery("banana", mode = SearchMode.KEYWORD)).hits.isEmpty(), "old text must be gone")
            } finally { idx.close() }
        }
    }

    @Test
    fun `deleting a file removes it from the index`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seed("gone.md", "# Doomed\nephemeral content here")
            val idx = fx.newIndex(FakeEmbedder("fake-v1"))
            try {
                fx.engine.onCommit { idx.onCommit(it) }
                assertTrue(idx.search(SearchQuery("ephemeral", mode = SearchMode.KEYWORD)).hits.isNotEmpty())

                val rev = fx.engine.read("gone.md")!!.revision
                fx.engine.delete("gone.md", expectedRevision = rev, author = INDEXER)
                idx.waitIdle()

                assertTrue(idx.search(SearchQuery("ephemeral", mode = SearchMode.KEYWORD)).hits.isEmpty(), "deleted file must leave the index")
                assertEquals(0, idx.docCount())
            } finally { idx.close() }
        }
    }

    @Test
    fun `moving a file reindexes under the new path`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seed("old/place.md", "# Movable\nportable knowledge unit")
            val idx = fx.newIndex(FakeEmbedder("fake-v1"))
            try {
                fx.engine.onCommit { idx.onCommit(it) }
                val rev = fx.engine.read("old/place.md")!!.revision
                fx.engine.move("old/place.md", "new/place.md", expectedRevision = rev, author = INDEXER)
                idx.waitIdle()

                val hit = idx.search(SearchQuery("portable knowledge", mode = SearchMode.KEYWORD)).hits.firstOrNull()
                assertEquals("new/place.md", hit?.path, "content should be found under the new path only")
                assertEquals(1, idx.docCount())
            } finally { idx.close() }
        }
    }
}
