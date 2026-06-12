package dev.svod.engine.index

import dev.svod.engine.core.GitCli
import dev.svod.engine.core.WriteOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Embedder that blocks inside the first embedding call until the test releases it. This
 * pins the indexer thread so we can prove, deterministically, that writes do not wait on
 * indexing — no timing assumptions.
 */
private class GatedEmbedder(override val model: String, override val dim: Int = 64) : Embedder {
    private val gate = CountDownLatch(1)
    fun release() = gate.countDown()

    override fun embedPassages(texts: List<String>): List<FloatArray> {
        gate.await()
        return texts.map { vec(it) }
    }

    override fun embedQuery(text: String): FloatArray = vec(text)

    private fun vec(text: String): FloatArray {
        val v = FloatArray(dim)
        for (t in text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }) {
            v[Math.floorMod(t.hashCode(), dim)] += 1f
        }
        v[0] += 0.001f
        return v
    }
}

class IndexConcurrencyTest {

    @Test
    fun `indexing runs off the write path and never blocks or corrupts the single writer`() = runBlocking {
        IndexFixture.create().use { fx ->
            val gated = GatedEmbedder("gated")
            val idx = fx.newIndex(gated)
            try {
                fx.engine.onCommit { idx.onCommit(it) }

                val n = 60
                // The indexer is blocked on the gate; if writes depended on indexing they
                // would deadlock here. They must all complete regardless.
                val outcomes = withContext(Dispatchers.Default) {
                    (0 until n).map { i ->
                        async { fx.engine.write("c/file-$i.md", "# F$i\nbody token-$i here", expectedRevision = null, author = INDEXER) }
                    }.awaitAll()
                }
                assertTrue(outcomes.all { it is WriteOutcome.Success }, "all writes must succeed while indexing is blocked")

                // Indexer made no progress (still gated) — proves writes never waited on it.
                assertEquals(0, idx.docCount(), "indexer is gated, so nothing should be indexed yet")

                // Single-writer integrity from step 1 is untouched by concurrent indexing.
                assertTrue(GitCli.isWorkingTreeClean(fx.root), "tree must equal HEAD")
                assertTrue(GitCli.fsckClean(fx.root), "git object store intact")

                // Release the indexer; it catches up to HEAD.
                gated.release()
                idx.waitIdle()
                assertEquals(n, idx.docCount(), "index becomes consistent once unblocked")
                assertEquals(fx.engine.head(), idx.headCommitIndexed())
                assertEquals("c/file-7.md", idx.search(SearchQuery("token-7", mode = SearchMode.KEYWORD)).hits.firstOrNull()?.path)
            } finally { idx.close() }
        }
    }
}
