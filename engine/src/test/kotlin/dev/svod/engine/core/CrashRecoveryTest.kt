package dev.svod.engine.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.stream.Collectors
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val BOB = Author("bob", "bob@svod.test")

class CrashRecoveryTest {

    private fun orphanTmpCount(fx: VaultFixture): Long =
        Files.walk(fx.root).use { s ->
            s.filter { Files.isRegularFile(it) && AtomicFile.isTmp(it) }.collect(Collectors.toList())
        }.size.toLong()

    @Test
    fun `crash before rename loses only the in-flight write and cleans tmp`() = runBlocking {
        for (point in listOf(CrashPoint.AFTER_TMP_WRITE, CrashPoint.AFTER_FSYNC)) {
            VaultFixture.create().use { fx ->
                val e1 = fx.open()
                val base = e1.write("doc.md", "durable\n", expectedRevision = null, author = BOB) as WriteOutcome.Success

                e1.crash.armed = point
                var crashed = false
                try {
                    e1.write("doc.md", "interrupted\n", expectedRevision = base.revision, author = BOB)
                } catch (ex: CrashSimulationException) {
                    crashed = true
                    assertEquals(point, ex.at)
                }
                assertTrue(crashed, "write should have crashed at $point")
                assertTrue(orphanTmpCount(fx) >= 1, "a tmp file should remain after crash at $point")

                fx.simulateCrash()
                val e2 = fx.open() // runs recovery

                // the interrupted write never reached disk; base content is intact
                assertEquals("durable\n", e2.read("doc.md")!!.text, "base must survive crash at $point")
                assertEquals(0L, orphanTmpCount(fx), "recovery must remove orphan tmp ($point)")
                assertTrue(GitCli.isWorkingTreeClean(fx.root), "tree clean after recovery ($point)")
                assertTrue(GitCli.fsckClean(fx.root), "fsck clean after recovery ($point)")
            }
        }
    }

    @Test
    fun `crash after rename but before commit completes the write (file never lost)`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e1 = fx.open()

            e1.crash.armed = CrashPoint.AFTER_RENAME
            var crashed = false
            try {
                e1.write("recovered.md", "I reached disk\n", expectedRevision = null, author = BOB)
            } catch (ex: CrashSimulationException) {
                crashed = true
            }
            assertTrue(crashed)
            // file is on disk (rename happened) but not yet committed
            assertTrue(fx.root.resolve("recovered.md").exists())
            assertEquals(false, GitCli.isWorkingTreeClean(fx.root), "uncommitted change should be present pre-recovery")

            fx.simulateCrash()
            val e2 = fx.open() // recovery completes the write via a recovery commit

            val read = e2.read("recovered.md")
            assertNotNull(read, "the file that reached disk must be recovered, never lost")
            assertEquals("I reached disk\n", read.text)
            assertEquals(0L, orphanTmpCount(fx))
            assertTrue(GitCli.isWorkingTreeClean(fx.root), "tree clean after recovery commit")
            assertTrue(GitCli.fsckClean(fx.root))

            // recovery is recorded in history under the recovery identity
            val hist = e2.history("recovered.md")
            assertTrue(hist.any { it.authorName == Author.RECOVERY.name }, "recovery commit should appear in history")
        }
    }

    @Test
    fun `recovery removes a stray orphan tmp on open`() = runBlocking {
        VaultFixture.create().use { fx ->
            fx.open().also { it.write("a.md", "x\n", null, BOB) }
            fx.simulateCrash()

            // simulate residue from an interrupted write that left a tmp behind
            val stray = fx.root.resolve("sub").resolve("ghost.md.deadbeef${AtomicFile.TMP_SUFFIX}")
            Files.createDirectories(stray.parent)
            Files.writeString(stray, "garbage")
            assertTrue(stray.exists())

            fx.open()
            assertTrue(!stray.exists(), "orphan tmp must be deleted on open")
            assertTrue(GitCli.isWorkingTreeClean(fx.root))
        }
    }

    @Test
    fun `second instance on the same vault is refused (single-instance)`() = runBlocking {
        VaultFixture.create().use { fx ->
            fx.open()
            var refused = false
            try {
                SvodEngine.open(fx.root, fx.scope)
            } catch (ex: VaultLockedException) {
                refused = true
            }
            assertTrue(refused, "second open must throw VaultLockedException")
        }
    }
}
