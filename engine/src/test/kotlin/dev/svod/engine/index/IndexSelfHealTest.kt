package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexSelfHealTest {

    private suspend fun IndexFixture.seedSmallCorpus() {
        seed("a.md", "# Alpha\nthe quick brown fox jumps")
        seed("b.md", "# Beta\nlazy dogs sleep all day")
        seed("c/c.md", "# Gamma\nelectrons orbit the nucleus")
    }

    @Test
    fun `index reconstructs exactly from git HEAD after a full wipe`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seedSmallCorpus()

            val before = fx.newIndex(FakeEmbedder("fake-v1"))
            val beforeCount: Int
            val beforeTop: String?
            val beforeHead: String?
            try {
                beforeCount = before.docCount()
                beforeTop = before.search(SearchQuery("electrons nucleus", mode = SearchMode.KEYWORD)).hits.firstOrNull()?.path
                beforeHead = before.headCommitIndexed()
            } finally { before.close() }

            // wipe the entire index directory (simulates corruption / cold start)
            Files.walk(fx.indexDir).use { s ->
                s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
            assertTrue(!Files.exists(fx.indexDir) || Files.list(fx.indexDir).use { it.findAny().isEmpty })

            // reopen → no meta → full reindex from HEAD
            val after = fx.newIndex(FakeEmbedder("fake-v1"))
            try {
                assertEquals(beforeCount, after.docCount(), "doc count must match after rebuild from HEAD")
                assertEquals(beforeTop, after.search(SearchQuery("electrons nucleus", mode = SearchMode.KEYWORD)).hits.firstOrNull()?.path)
                assertEquals(beforeHead, after.headCommitIndexed(), "rebuilt index must point at the same HEAD")
                assertEquals(fx.engine.head(), after.headCommitIndexed())
            } finally { after.close() }
        }
    }

    @Test
    fun `reconcile heals drift when an index update was missed`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seedSmallCorpus()
            val idx = fx.newIndex(FakeEmbedder("fake-v1"))
            try {
                // a write that the index never hears about (no onCommit wiring) → drift vs HEAD
                fx.engine.write("late.md", "# Late\nsynchrotron radiation discovery", expectedRevision = null, author = INDEXER)
                assertTrue(idx.search(SearchQuery("synchrotron", mode = SearchMode.KEYWORD)).hits.isEmpty(), "drifted doc not yet indexed")

                idx.reconcileNow() // self-heal vs git HEAD

                val hit = idx.search(SearchQuery("synchrotron", mode = SearchMode.KEYWORD)).hits.firstOrNull()
                assertEquals("late.md", hit?.path, "self-heal must pick up the missed file")
                assertEquals(fx.engine.head(), idx.headCommitIndexed())
            } finally { idx.close() }
        }
    }
}
