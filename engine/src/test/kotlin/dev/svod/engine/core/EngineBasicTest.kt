package dev.svod.engine.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.path.exists
import kotlin.io.path.writeText

private val ALICE = Author("alice", "alice@svod.test")

class EngineBasicTest {

    @Test
    fun `create read update delete restore move lifecycle`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()

            // create
            val created = e.write("notes/hello.md", "# Hello\nworld\n", expectedRevision = null, author = ALICE)
            assertTrue(created is WriteOutcome.Success, "expected Success, got $created")
            created as WriteOutcome.Success
            assertTrue(created.revision.isNotEmpty())
            assertTrue(created.commit.isNotEmpty())

            // read back; revision matches
            val read = e.read("notes/hello.md")
            assertNotNull(read)
            assertEquals("# Hello\nworld\n", read.text)
            assertEquals(created.revision, read.revision)

            // stale write (wrong expected revision) -> conflict, no overwrite
            val stale = e.write("notes/hello.md", "garbage", expectedRevision = null, author = ALICE)
            assertTrue(stale is WriteOutcome.Conflict, "expected Conflict, got $stale")
            stale as WriteOutcome.Conflict
            assertEquals(created.revision, stale.current)
            assertEquals("# Hello\nworld\n", stale.currentContent)
            assertEquals("# Hello\nworld\n", e.read("notes/hello.md")!!.text) // unchanged

            // correct update
            val updated = e.write("notes/hello.md", "# Hello\nagain\n", expectedRevision = created.revision, author = ALICE)
            assertTrue(updated is WriteOutcome.Success)
            assertTrue(e.history("notes/hello.md").size >= 2)

            // soft delete -> file gone from tree, present in .trash
            val curRev = e.read("notes/hello.md")!!.revision
            val deleted = e.delete("notes/hello.md", expectedRevision = curRev, author = ALICE)
            assertTrue(deleted is WriteOutcome.Success, "expected Success, got $deleted")
            deleted as WriteOutcome.Success
            assertNull(e.read("notes/hello.md"))
            assertTrue(fx.root.resolve(deleted.path).exists(), "trashed file should exist at ${deleted.path}")
            assertTrue(deleted.path.startsWith(".trash/"))

            // restore back to original path
            val restored = e.restore(deleted.path, author = ALICE)
            assertTrue(restored is WriteOutcome.Success, "expected Success, got $restored")
            assertEquals("# Hello\nagain\n", e.read("notes/hello.md")!!.text)

            // move
            val rev = e.read("notes/hello.md")!!.revision
            val moved = e.move("notes/hello.md", "archive/hello.md", expectedRevision = rev, author = ALICE)
            assertTrue(moved is WriteOutcome.Success, "expected Success, got $moved")
            assertNull(e.read("notes/hello.md"))
            assertEquals("# Hello\nagain\n", e.read("archive/hello.md")!!.text)

            assertTrue(GitCli.isWorkingTreeClean(fx.root), "tree should be clean after committed ops")
            assertTrue(GitCli.fsckClean(fx.root), "git fsck must be clean")
        }
    }

    @Test
    fun `delete and move on missing file return NotFound`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            assertTrue(e.delete("nope.md", expectedRevision = null, author = ALICE) is WriteOutcome.NotFound)
            assertTrue(e.move("nope.md", "x.md", expectedRevision = null, author = ALICE) is WriteOutcome.NotFound)
        }
    }

    @Test
    fun `an engine write commits only its own path, not unrelated working-tree drift`() = runBlocking {
        // Locks in the path-scoped commit (the fix for O(tree) commits on large vaults): a write must
        // stage only its own path, never sweep the whole working tree. The old `add .` would have
        // committed `drift.md` too — and walked every tracked file, which was ~37s on a 70k-file vault.
        VaultFixture.create().use { fx ->
            val e = fx.open()
            e.write("a.md", "first\n", expectedRevision = null, author = ALICE)
            // Untracked drift appears in the working tree, NOT via the engine.
            fx.root.resolve("drift.md").writeText("untracked\n")
            val w = e.write("b.md", "second\n", expectedRevision = null, author = ALICE)
            assertTrue(w is WriteOutcome.Success, "expected Success, got $w")
            assertTrue(e.history("b.md").isNotEmpty(), "the written path must be committed")
            assertTrue(e.history("drift.md").isEmpty(), "unrelated working-tree drift must NOT be swept into the commit")
        }
    }

    @Test
    fun `path traversal and reserved paths are rejected`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            for (bad in listOf("../escape.md", "a/../../b.md", ".git/config", ".svod/lock", ".trash/x.md", "")) {
                var threw = false
                try {
                    e.write(bad, "x", expectedRevision = null, author = ALICE)
                } catch (_: IllegalArgumentException) {
                    threw = true
                }
                assertTrue(threw, "expected rejection for '$bad'")
            }
        }
    }
}
