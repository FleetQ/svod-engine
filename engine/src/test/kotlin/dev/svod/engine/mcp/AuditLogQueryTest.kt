package dev.svod.engine.mcp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AuditLog.query]: read-only, newest first, tolerant of bad lines, stable `before` cursor. */
class AuditLogQueryTest {

    private fun line(ts: Long, agent: String, outcome: String, tool: String = "write", path: String? = "n.md") =
        buildString {
            append("""{"ts":$ts,"agentId":"$agent","tool":"$tool","outcome":"$outcome"""")
            if (path != null) append(""","path":"$path"""")
            append("}")
        }

    private fun trail(vararg lines: String): Path {
        val root = Files.createTempDirectory("svod-audit-query-")
        val f = AuditLog.fileFor(root)
        Files.createDirectories(f.parent)
        Files.writeString(f, lines.joinToString("\n", postfix = "\n"))
        return f
    }

    @Test
    fun `fileFor is the vault's audit log path`() {
        val root = Path.of("/tmp/v")
        assertEquals(root.resolve(".svod").resolve("audit").resolve("audit.log"), AuditLog.fileFor(root))
    }

    @Test
    fun `entries come back newest first with seq equal to the line number`() {
        val f = trail(line(1000, "a", "ok"), line(2000, "b", "conflict"), line(3000, "a", "blocked"))
        val p = AuditLog.query(f, limit = 100)
        assertEquals(listOf(3L, 2L, 1L), p.entries.map { it.seq })
        assertEquals(listOf(3000L, 2000L, 1000L), p.entries.map { it.ts })
        assertEquals("blocked", p.entries.first().outcome)
        assertEquals("n.md", p.entries.first().path)
        assertEquals(3, p.total)
        assertEquals(0, p.skipped)
        assertNull(p.nextBefore)
    }

    @Test
    fun `a garbage middle line and a truncated last line are skipped and counted, not thrown`() {
        val root = Files.createTempDirectory("svod-audit-query-bad-")
        val f = AuditLog.fileFor(root)
        Files.createDirectories(f.parent)
        Files.writeString(f, line(1000, "a", "ok") + "\nnot json at all\n" + line(2000, "a", "ok") + "\n" +
            """{"ts":3000,"agentId":"a","to""")   // half-written, no newline
        val p = AuditLog.query(f, limit = 100)
        assertEquals(listOf(3L, 1L), p.entries.map { it.seq }, "seq stays the real line number around a bad line")
        assertEquals(2, p.skipped)
        assertEquals(2, p.total)
    }

    @Test
    fun `unknown fields are tolerated`() {
        val f = trail("""{"ts":5,"agentId":"a","tool":"write","outcome":"ok","extra":42}""")
        val p = AuditLog.query(f, limit = 10)
        assertEquals(1, p.entries.size)
        assertEquals(0, p.skipped)
    }

    @Test
    fun `filters by agent, outcomes and since`() {
        val f = trail(
            line(1000, "a", "ok"), line(2000, "b", "conflict"), line(3000, "a", "blocked"),
            line(4000, "b", "ok"), line(5000, "a", "denied"),
        )
        assertEquals(listOf(5L, 3L, 1L), AuditLog.query(f, agent = "a", limit = 100).entries.map { it.seq })
        assertEquals(listOf(3L, 2L), AuditLog.query(f, outcomes = setOf("blocked", "conflict"), limit = 100).entries.map { it.seq })
        assertEquals(listOf(5L, 4L, 3L), AuditLog.query(f, sinceMs = 3000, limit = 100).entries.map { it.seq })
        val combined = AuditLog.query(f, agent = "b", outcomes = setOf("ok"), sinceMs = 2000, limit = 100)
        assertEquals(listOf(4L), combined.entries.map { it.seq })
        assertEquals(1, combined.total)
        assertEquals(5, AuditLog.query(f, outcomes = emptySet(), limit = 100).total, "empty outcome set = any outcome")
    }

    @Test
    fun `paging with before is stable when new lines are appended`() {
        val f = trail(line(1, "a", "ok"), line(2, "a", "ok"), line(3, "a", "ok"), line(4, "a", "ok"), line(5, "a", "ok"))
        val first = AuditLog.query(f, limit = 2)
        assertEquals(listOf(5L, 4L), first.entries.map { it.seq })
        assertEquals(4L, first.nextBefore)
        assertEquals(5, first.total)

        Files.writeString(f, line(6, "a", "ok") + "\n", java.nio.file.StandardOpenOption.APPEND)

        val second = AuditLog.query(f, before = first.nextBefore, limit = 2)
        assertEquals(listOf(3L, 2L), second.entries.map { it.seq }, "a newer line does not shift older pages")
        assertEquals(2L, second.nextBefore)
        val last = AuditLog.query(f, before = second.nextBefore, limit = 2)
        assertEquals(listOf(1L), last.entries.map { it.seq })
        assertNull(last.nextBefore, "no more matches")
        val exact = AuditLog.query(f, before = 3, limit = 2)
        assertEquals(listOf(2L, 1L), exact.entries.map { it.seq })
        assertNull(exact.nextBefore, "page ends exactly at the oldest match")
    }

    @Test
    fun `a missing trail is empty and nothing is created`() {
        val root = Files.createTempDirectory("svod-audit-query-none-")
        val f = AuditLog.fileFor(root)
        val p = AuditLog.query(f, limit = 100)
        assertEquals(0, p.total)
        assertTrue(p.entries.isEmpty())
        assertFalse(Files.exists(root.resolve(".svod")), "query must not create directories")
    }

    @Test
    fun `query leaves the file byte-identical and entries written by record are read back`() {
        val root = Files.createTempDirectory("svod-audit-query-rec-")
        val log = AuditLog(AuditLog.fileFor(root), clock = { 42L })
        log.record("scribe", "write", "ok", "x.md", revision = "r1", detail = "c1")
        val before = Files.readAllBytes(AuditLog.fileFor(root))
        val p = AuditLog.query(AuditLog.fileFor(root), limit = 10)
        val e = p.entries.single()
        assertEquals(1L, e.seq)
        assertEquals(42L, e.ts)
        assertEquals("scribe", e.agentId)
        assertEquals("r1", e.revision)
        assertEquals("c1", e.detail)
        assertTrue(before.contentEquals(Files.readAllBytes(AuditLog.fileFor(root))))
    }
}
