package dev.svod.engine.core

import dev.svod.engine.security.SecretScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WriteBatchTest {

    private val author = Author("import", "i@x")
    private fun engineOn(scan: Boolean = false) =
        SvodEngine.open(Files.createTempDirectory("svod-batch-"), CoroutineScope(SupervisorJob() + Dispatchers.Default), SecretScanner(scan))

    @Test
    fun `writeBatch writes many files in a single commit`() = runBlocking {
        engineOn().use { engine ->
            val entries = (1..10).map { BatchEntry.Text("n/$it.md", "# Note $it\nбяло") } +
                BatchEntry.Bytes("assets/x.bin", byteArrayOf(0x00, 0xFF.toByte(), 0x10))
            val r = engine.writeBatch(entries, author)

            assertEquals(11, r.written.size)
            assertTrue(r.unchanged.isEmpty() && r.skipped.isEmpty())
            assertNotNull(r.commit)
            // every file shares ONE commit — that's the whole point
            val c = engine.history("n/1.md").first().commit
            assertEquals(c, engine.history("n/7.md").first().commit, "all batch files committed together")
            assertEquals(c, engine.history("assets/x.bin").first().commit)
            assertEquals(r.commit, c)
            assertContentEquals(byteArrayOf(0x00, 0xFF.toByte(), 0x10), engine.readBytes("assets/x.bin"))
        }
    }

    @Test
    fun `writeBatch is idempotent and never clobbers`() = runBlocking {
        engineOn().use { engine ->
            val entries = listOf(BatchEntry.Text("a.md", "# A"), BatchEntry.Text("b.md", "# B"))
            engine.writeBatch(entries, author)
            val head = engine.head()

            // re-run: all unchanged, NO new commit
            val again = engine.writeBatch(entries, author)
            assertEquals(listOf("a.md", "b.md"), again.unchanged)
            assertTrue(again.written.isEmpty())
            assertEquals(head, engine.head(), "a no-op batch creates no commit")

            // edit one on disk, re-batch with different content for a.md ⇒ a.md skipped, b.md unchanged
            engine.write("a.md", "# A edited", engine.read("a.md")!!.revision, author)
            val third = engine.writeBatch(listOf(BatchEntry.Text("a.md", "# A"), BatchEntry.Text("b.md", "# B")), author)
            assertEquals(listOf("a.md"), third.skipped, "differing file is skipped, not overwritten")
            assertEquals("# A edited", engine.read("a.md")!!.text, "local edit preserved")
        }
    }

    @Test
    fun `overwrite batch with a stale expected revision is a conflict, not a clobber`() = runBlocking {
        engineOn().use { engine ->
            engine.write("a.md", "# A original", null, author)
            val staleRev = engine.read("a.md")!!.revision
            // Someone edits a.md after we classified it at staleRev.
            engine.write("a.md", "# A edited locally", staleRev, author)

            // An overwrite batch carrying the now-stale expected revision must NOT clobber the edit.
            val r = engine.writeBatch(
                listOf(BatchEntry.Text("a.md", "# A from source"), BatchEntry.Text("b.md", "# B new")),
                author, overwrite = true, expected = mapOf("a.md" to staleRev, "b.md" to null),
            )
            assertEquals(listOf("a.md"), r.conflicts, "the raced edit is a conflict")
            assertEquals(listOf("b.md"), r.written, "the unraced file still writes")
            assertEquals("# A edited locally", engine.read("a.md")!!.text, "local edit preserved, never clobbered")

            // With the CURRENT revision as expected, the overwrite proceeds.
            val cur = engine.read("a.md")!!.revision
            val r2 = engine.writeBatch(
                listOf(BatchEntry.Text("a.md", "# A from source")),
                author, overwrite = true, expected = mapOf("a.md" to cur),
            )
            assertEquals(listOf("a.md"), r2.written)
            assertTrue(r2.conflicts.isEmpty())
            assertEquals("# A from source", engine.read("a.md")!!.text)
        }
    }

    @Test
    fun `benchmark batched import throughput`() = runBlocking {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("svod.perf") == "true")
        val n = System.getProperty("svod.notes", "3000").toInt()
        val src = Files.createTempDirectory("svod-bench-src-")
        repeat(n) { Files.writeString(src.resolve("note$it.md"), "---\ntags: [bench]\n---\n# Note $it\nsee [[note${(it + 1) % n}]] — Cyrillic тяло $it\n") }
        engineOn().use { engine ->
            val t0 = System.nanoTime()
            val r = dev.svod.engine.migrate.ObsidianImport.import(src, engine)
            val sec = (System.nanoTime() - t0) / 1e9
            println("BATCHED IMPORT: ${r.imported.size} notes in %.2fs = %.1f notes/sec".format(sec, r.imported.size / sec))
            assertEquals(n, r.imported.size)
        }
    }

    @Test
    fun `writeBatch skips a secret-laden text entry but writes the rest`() = runBlocking {
        engineOn(scan = true).use { engine ->
            val pem = "-----BEGIN PRIVATE KEY-----\nMIIabcDEF\n-----END PRIVATE KEY-----"
            val r = engine.writeBatch(listOf(
                BatchEntry.Text("ok.md", "# fine"),
                BatchEntry.Text("leak.md", pem),
            ), author)
            assertEquals(listOf("ok.md"), r.written)
            assertEquals(listOf("leak.md"), r.skipped, "secret-laden entry blocked from the batch")
            assertTrue(engine.read("leak.md") == null, "the secret file never reached the tree")
        }
    }
}
