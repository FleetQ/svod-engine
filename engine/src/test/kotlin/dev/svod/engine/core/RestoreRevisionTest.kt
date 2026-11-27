package dev.svod.engine.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val RITA = Author("rita", "rita@svod.test")
private val OTTO = Author("otto", "otto@svod.test")

class RestoreRevisionTest {

    @Test
    fun `restoreRevision puts the note back to an earlier commit in one attributed commit`(): Unit = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            val v1 = assertIs<WriteOutcome.Success>(e.write("notes/a.md", "version one\n", null, RITA))
            val v2 = assertIs<WriteOutcome.Success>(e.write("notes/a.md", "version two\n", v1.revision, RITA))

            // an abbreviated commit id resolves, and the message names the full id
            val restored = assertIs<WriteOutcome.Success>(e.restoreRevision("notes/a.md", v1.commit.take(10), v2.revision, OTTO))
            assertEquals("version one\n", e.read("notes/a.md")!!.text)
            assertEquals(v1.revision, restored.revision, "the restored blob equals the v1 blob")
            val head = e.history("notes/a.md", 1).single()
            assertEquals(restored.commit, head.commit)
            assertTrue(head.message.startsWith("restore: notes/a.md to ${v1.commit}"), head.message)
            assertEquals("otto", head.authorName)
            assertEquals("otto@svod.test", head.authorEmail)
        }
    }

    @Test
    fun `a stale expectedRevision is a conflict and leaves the note untouched`(): Unit = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            val v1 = assertIs<WriteOutcome.Success>(e.write("n.md", "one\n", null, RITA))
            val v2 = assertIs<WriteOutcome.Success>(e.write("n.md", "two\n", v1.revision, RITA))
            val before = e.history("n.md").size

            val c = assertIs<WriteOutcome.Conflict>(e.restoreRevision("n.md", v1.commit, v1.revision, OTTO))
            assertEquals(v2.revision, c.current)
            assertEquals("two\n", c.currentContent)
            assertEquals("two\n", e.read("n.md")!!.text)
            assertEquals(before, e.history("n.md").size, "no commit on conflict")

            // null expected means the note must not exist: also a conflict here
            assertIs<WriteOutcome.Conflict>(e.restoreRevision("n.md", v1.commit, null, OTTO))
        }
    }

    @Test
    fun `unknown revisions are refused and a note absent at the commit is not found`(): Unit = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            val first = assertIs<WriteOutcome.Success>(e.write("other.md", "x\n", null, RITA))
            val n = assertIs<WriteOutcome.Success>(e.write("n.md", "one\n", null, RITA))

            assertFailsWith<IllegalArgumentException> { e.restoreRevision("n.md", "0".repeat(40), n.revision, OTTO) }
            assertFailsWith<IllegalArgumentException> { e.restoreRevision("n.md", "no-such-ref", n.revision, OTTO) }
            // a blob id is not a commit
            assertFailsWith<IllegalArgumentException> { e.restoreRevision("n.md", n.revision, n.revision, OTTO) }

            val nf = assertIs<WriteOutcome.NotFound>(e.restoreRevision("n.md", first.commit, n.revision, OTTO))
            assertEquals("n.md@${first.commit}", nf.path)
            assertEquals("one\n", e.read("n.md")!!.text)
        }
    }

    @Test
    fun `a deleted note can be brought back from history when expected is null`(): Unit = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            val v1 = assertIs<WriteOutcome.Success>(e.write("gone.md", "keep me\n", null, RITA))
            assertIs<WriteOutcome.Success>(e.delete("gone.md", v1.revision, RITA))

            assertIs<WriteOutcome.Success>(e.restoreRevision("gone.md", v1.commit, null, OTTO))
            assertEquals("keep me\n", e.read("gone.md")!!.text)
        }
    }

    @Test
    fun `a content override is written instead of the old bytes`(): Unit = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            val v1 = assertIs<WriteOutcome.Success>(e.write("o.md", "one\n", null, RITA))
            val v2 = assertIs<WriteOutcome.Success>(e.write("o.md", "two\n", v1.revision, RITA))
            assertIs<WriteOutcome.Success>(e.restoreRevision("o.md", v1.commit, v2.revision, OTTO, content = "---\nstatus: provisional\n---\none\n"))
            assertEquals("---\nstatus: provisional\n---\none\n", e.read("o.md")!!.text)
            assertTrue(e.history("o.md", 1).single().message.startsWith("restore: o.md to ${v1.commit}"))
        }
    }
}
