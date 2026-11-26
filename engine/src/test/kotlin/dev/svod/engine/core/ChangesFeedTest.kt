package dev.svod.engine.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The vault-wide change feed: commits newest first, with changed paths classified per commit. */
class ChangesFeedTest {
    private val ui = Author("Ui", "ui@svod.local")
    private val bot = Author("Scribe", "scribe@agents.test", agent = "scribe")

    private fun ok(o: WriteOutcome): WriteOutcome.Success = o as? WriteOutcome.Success ?: error("expected success, got $o")

    @Test
    fun `lists commits newest first with author, message and agent`() {
        VaultFixture.create().use { fx ->
            val engine = fx.open()
            runBlocking {
                val a = ok(engine.write("a.md", "# A\nalpha", null, ui))
                val b = ok(engine.write("notes/b.md", "# B\nbeta", null, bot))

                val page = engine.changes(null, null, 50)
                assertEquals(b.commit, page.head)
                assertEquals(b.commit, page.changes[0].commit)
                assertEquals(a.commit, page.changes[1].commit)
                val top = page.changes[0]
                assertEquals("Scribe", top.authorName)
                assertEquals("scribe@agents.test", top.authorEmail)
                assertEquals("scribe", top.agent)
                assertTrue(top.message.isNotBlank())
                assertEquals(listOf(PathChange("notes/b.md", ChangeKind.ADD)), top.paths)
                assertEquals(1, top.pathCount)
                assertFalse(top.pathsTruncated)
                assertNull(page.changes[1].agent)
                assertEquals("Ui", page.changes[1].authorName)
                assertNull(page.nextCursor, "everything fits in one page")
            }
        }
    }

    @Test
    fun `since a commit leaves out that commit and everything older`() {
        VaultFixture.create().use { fx ->
            val engine = fx.open()
            runBlocking {
                val a = ok(engine.write("a.md", "a", null, ui))
                val b = ok(engine.write("b.md", "b", null, ui))
                val c = ok(engine.write("c.md", "c", null, ui))
                val page = engine.changes(a.commit, null, 50)
                assertEquals(listOf(c.commit, b.commit), page.changes.map { it.commit })
                assertTrue(engine.changes(c.commit, null, 50).changes.isEmpty(), "nothing new since head")
            }
        }
    }

    @Test
    fun `since a date keeps only commits at or after it`() {
        VaultFixture.create().use { fx ->
            val engine = fx.open()
            runBlocking {
                val a = ok(engine.write("a.md", "a", null, ui))
                val all = engine.changes("2000-01-01", null, 200).changes.map { it.commit }
                assertTrue(a.commit in all)
                assertEquals(all, engine.changes("2000-01-01T00:00:00Z", null, 200).changes.map { it.commit })
                val tomorrow = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2).toString()
                assertTrue(engine.changes(tomorrow, null, 50).changes.isEmpty())
                assertTrue(engine.changes(java.time.Instant.now().plusSeconds(3600).toString(), null, 50).changes.isEmpty())
            }
        }
    }

    @Test
    fun `unparsable since or unknown before is an IllegalArgumentException`() {
        VaultFixture.create().use { fx ->
            val engine = fx.open()
            runBlocking {
                engine.write("a.md", "a", null, ui)
                assertFailsWith<IllegalArgumentException> { engine.changes("not-a-date-or-commit", null, 50) }
                assertFailsWith<IllegalArgumentException> { engine.changes(null, "0123456789abcdef0123456789abcdef01234567", 50) }
            }
        }
    }

    @Test
    fun `pages with before = nextCursor`() {
        VaultFixture.create().use { fx ->
            val engine = fx.open()
            runBlocking {
                val ids = (1..5).map { ok(engine.write("p$it.md", "p$it", null, ui)).commit }.reversed()
                val first = engine.changes(null, null, 2)
                assertEquals(ids.take(2), first.changes.map { it.commit })
                assertEquals(ids[1], first.nextCursor)
                val second = engine.changes(null, first.nextCursor, 2)
                assertEquals(ids.subList(2, 4), second.changes.map { it.commit })
                assertEquals(first.head, second.head, "head is always the current HEAD")
                // since bounds the paging too
                val third = engine.changes(ids[4], second.nextCursor, 2)
                assertTrue(third.changes.isEmpty())
                assertNull(third.nextCursor)
            }
        }
    }

    @Test
    fun `kinds - add, modify, rename, soft delete, restore`() {
        VaultFixture.create().use { fx ->
            val engine = fx.open()
            runBlocking {
                val add = ok(engine.write("n/a.md", "# A\nsome text that stays the same", null, ui))
                val mod = ok(engine.write("n/a.md", "# A\nsome text that stays the same\nmore", add.revision, ui))
                val mv = ok(engine.move("n/a.md", "n/b.md", mod.revision, ui))
                val del = ok(engine.delete("n/b.md", mv.revision, ui))
                assertTrue(del.path.startsWith(".trash/"), del.path)
                val res = ok(engine.restore(del.path, null, ui))

                val byCommit = engine.changes(null, null, 50).changes.associateBy { it.commit }
                assertEquals(listOf(PathChange("n/a.md", ChangeKind.ADD)), byCommit.getValue(add.commit).paths)
                assertEquals(listOf(PathChange("n/a.md", ChangeKind.MODIFY)), byCommit.getValue(mod.commit).paths)
                assertEquals(listOf(PathChange("n/b.md", ChangeKind.RENAME, "n/a.md")), byCommit.getValue(mv.commit).paths)
                assertEquals(listOf(PathChange(del.path, ChangeKind.DELETE, "n/b.md")), byCommit.getValue(del.commit).paths)
                val restored = byCommit.getValue(res.commit).paths.single()
                assertEquals(ChangeKind.RESTORE, restored.kind)
                assertEquals(del.path, restored.oldPath)
                assertEquals(res.path, restored.path)
            }
        }
    }

    @Test
    fun `paths are capped per commit with a count and a flag`() {
        VaultFixture.create().use { fx ->
            val engine = fx.open()
            runBlocking {
                val r = engine.writeBatch((1..205).map { BatchEntry.Text("bulk/n$it.md", "note $it") }, ui)
                val entry = engine.changes(null, null, 1).changes.single()
                assertEquals(r.commit, entry.commit)
                assertEquals(205, entry.pathCount)
                assertEquals(200, entry.paths.size)
                assertTrue(entry.pathsTruncated)
                assertNotNull(engine.changes(null, null, 1).nextCursor)
            }
        }
    }
}
