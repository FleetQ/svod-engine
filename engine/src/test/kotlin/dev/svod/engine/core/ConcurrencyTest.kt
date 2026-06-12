package dev.svod.engine.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrencyTest {

    private fun agent(i: Int) = Author("agent-$i", "agent-$i@svod.test")

    @Test
    fun `parallel writes to distinct files never lose a file`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            val n = 250
            withContext(Dispatchers.Default) {
                (0 until n).map { i ->
                    async {
                        val out = e.write("docs/file-$i.md", "content of $i\n", expectedRevision = null, author = agent(i))
                        assertTrue(out is WriteOutcome.Success, "write $i failed: $out")
                    }
                }.awaitAll()
            }

            // every file is present and correct
            val files = e.list()
            assertEquals(n, files.count { it.startsWith("docs/file-") }, "all $n files must exist")
            for (i in 0 until n) {
                assertEquals("content of $i\n", e.read("docs/file-$i.md")?.text, "file $i content")
            }
            // one commit per write, plus the initial scaffold commit
            assertEquals(n + 1, GitCli.commitCount(fx.root))
            assertTrue(GitCli.isWorkingTreeClean(fx.root))
            assertTrue(GitCli.fsckClean(fx.root))
        }
    }

    @Test
    fun `two writers from the same base revision cannot both win (no lost update)`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            val base = e.write("contended.md", "base\n", expectedRevision = null, author = agent(0)) as WriteOutcome.Success

            // Both read the same base revision, then both attempt to write concurrently.
            val results = coroutineScope {
                withContext(Dispatchers.Default) {
                    listOf(
                        async { e.write("contended.md", "from-A\n", expectedRevision = base.revision, author = agent(1)) },
                        async { e.write("contended.md", "from-B\n", expectedRevision = base.revision, author = agent(2)) },
                    ).awaitAll()
                }
            }

            val successes = results.filterIsInstance<WriteOutcome.Success>()
            val conflicts = results.filterIsInstance<WriteOutcome.Conflict>()
            assertEquals(1, successes.size, "exactly one writer may win: $results")
            assertEquals(1, conflicts.size, "the other must conflict: $results")

            // The loser's conflict reports the winner's content — no silent overwrite.
            val winnerText = e.read("contended.md")!!.text
            assertEquals(winnerText, conflicts[0].currentContent)
            assertTrue(winnerText == "from-A\n" || winnerText == "from-B\n")
        }
    }

    @Test
    fun `many writers contend on one file with retry, every committed write survives`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            e.write("log.md", "start\n", expectedRevision = null, author = agent(0))

            val writers = 40
            val committed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            withContext(Dispatchers.Default) {
                (0 until writers).map { i ->
                    async {
                        val tag = "writer-$i\n"
                        // optimistic retry loop until our write lands
                        while (true) {
                            val cur = e.read("log.md")!!
                            val out = e.write("log.md", cur.text + tag, expectedRevision = cur.revision, author = agent(i))
                            if (out is WriteOutcome.Success) { committed.add(tag); break }
                        }
                    }
                }.awaitAll()
            }

            val finalText = e.read("log.md")!!.text
            // Every writer's line is present exactly once: no lost updates under contention.
            for (i in 0 until writers) {
                assertEquals(1, Regex("^writer-$i$", RegexOption.MULTILINE).findAll(finalText).count(), "writer-$i line count")
            }
            assertEquals(writers, committed.size)
            assertTrue(GitCli.isWorkingTreeClean(fx.root))
            assertTrue(GitCli.fsckClean(fx.root))
        }
    }

    @Test
    fun `randomized fuzz keeps tree and history consistent`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            val rnd = Random(1337)
            val paths = (0 until 12).map { "n/file-$it.md" }
            // seed a few files
            for (p in paths.take(6)) e.write(p, "seed\n", expectedRevision = null, author = agent(0))

            val errors = AtomicInteger(0)
            withContext(Dispatchers.Default) {
                (0 until 60).map { iter ->
                    launch {
                        repeat(15) {
                            try {
                                val p = paths[rnd.nextInt(paths.size)]
                                when (rnd.nextInt(5)) {
                                    0 -> e.write(p, "v-${rnd.nextInt(1_000_000)}\n", e.read(p)?.revision, agent(iter))
                                    1 -> e.read(p)?.let { e.write(p, it.text + "x\n", it.revision, agent(iter)) }
                                    2 -> e.read(p)?.let { e.delete(p, it.revision, agent(iter)) }
                                    3 -> {
                                        val q = paths[rnd.nextInt(paths.size)]
                                        e.read(p)?.let { if (q != p) e.move(p, q, it.revision, agent(iter)) }
                                    }
                                    4 -> e.read(p)
                                }
                            } catch (ex: IllegalArgumentException) {
                                // legal: e.g. value-class validation on a transient path — not an integrity failure
                            } catch (ex: Throwable) {
                                errors.incrementAndGet()
                                System.err.println("fuzz op threw: $ex")
                            }
                        }
                    }
                }.forEach { it.join() }
            }

            assertEquals(0, errors.get(), "engine must never throw on the write path under fuzz")
            assertTrue(GitCli.isWorkingTreeClean(fx.root), "tree must equal HEAD after fuzz")
            assertTrue(GitCli.fsckClean(fx.root), "git object store must be intact after fuzz")
            // every listed file is readable
            for (p in e.list()) assertTrue(e.read(p) != null, "listed file $p must be readable")
        }
    }
}
