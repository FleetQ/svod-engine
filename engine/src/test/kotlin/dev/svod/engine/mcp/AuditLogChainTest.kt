package dev.svod.engine.mcp

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditLogChainTest {

    private val tmp: Path = Files.createTempDirectory("svod-audit-chain-")
    private val file: Path = AuditLog.fileFor(tmp)
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun cleanup() { tmp.toFile().deleteRecursively() }

    private fun lines(): List<String> = Files.readAllLines(file).filter { it.isNotBlank() }
    private fun entry(line: String): AuditEntry = json.decodeFromString(AuditEntry.serializer(), line)
    private fun writeLines(ls: List<String>) = Files.writeString(file, ls.joinToString("\n") + "\n")

    @Test
    fun `entries are chained from genesis`() {
        val log = AuditLog(file)
        log.record("a", "write", "ok", "n.md")
        log.record("a", "edit", "ok", "n.md")
        val (first, second) = lines().map(::entry)
        assertEquals("0".repeat(64), first.prevHash)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(first.hash!!), first.hash)
        assertEquals(AuditLog.hashOf(first), first.hash)
        assertEquals(first.hash, second.prevHash)
        assertEquals(AuditLog.hashOf(second), second.hash)
        assertEquals(AuditChainReport.Status.OK, AuditLog.verify(file).status)
    }

    @Test
    fun `two instances on the same file keep one chain`() {
        val a = AuditLog(file)
        val b = AuditLog(file)
        a.record("a", "write", "ok", "1.md")
        b.record("b", "write", "ok", "2.md")
        a.record("a", "write", "ok", "3.md")
        val es = lines().map(::entry)
        assertEquals(es[0].hash, es[1].prevHash)
        assertEquals(es[1].hash, es[2].prevHash)
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.OK, r.status, r.toString())
        assertEquals(3, r.chained)
    }

    @Test
    fun `concurrent writers from two instances keep the chain intact`() {
        val a = AuditLog(file)
        val b = AuditLog(file)
        val threads = (0 until 8).map { t ->
            Thread { repeat(25) { i -> (if (t % 2 == 0) a else b).record("t$t", "write", "ok", "$i.md") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.OK, r.status, r.toString())
        assertEquals(200, r.chained)
    }

    @Test
    fun `older unchained lines count as pre-chain and stay unchanged`() {
        val old = """{"ts":1,"agentId":"a","tool":"write","outcome":"ok","path":"old.md"}"""
        Files.createDirectories(file.parent)
        Files.writeString(file, old + "\n")
        AuditLog(file).record("a", "write", "ok", "new.md")
        val ls = lines()
        assertEquals(old, ls[0])
        assertEquals("0".repeat(64), entry(ls[1]).prevHash)
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.OK, r.status, r.toString())
        assertEquals(1, r.preChain)
        assertEquals(1, r.chained)
        // Old lines still read back.
        assertEquals(2, AuditLog(file).entries().size)
    }

    @Test
    fun `an edited line is reported`() {
        val log = AuditLog(file)
        repeat(3) { log.record("a", "write", "ok", "n$it.md") }
        val ls = lines().toMutableList()
        ls[1] = ls[1].replace("n1.md", "evil.md")
        writeLines(ls)
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.BROKEN, r.status)
        assertEquals(2L, r.brokenLine)
        assertTrue("hash" in r.reason!!, r.reason)
    }

    @Test
    fun `a removed line is reported`() {
        val log = AuditLog(file)
        repeat(3) { log.record("a", "write", "ok", "n$it.md") }
        val ls = lines().toMutableList()
        ls.removeAt(1)
        writeLines(ls)
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.BROKEN, r.status)
        assertEquals(2L, r.brokenLine)
        assertTrue("prevHash" in r.reason!!, r.reason)
    }

    @Test
    fun `a reordered line is reported`() {
        val log = AuditLog(file)
        repeat(3) { log.record("a", "write", "ok", "n$it.md") }
        val ls = lines()
        writeLines(listOf(ls[0], ls[2], ls[1]))
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.BROKEN, r.status)
        assertEquals(2L, r.brokenLine)
    }

    @Test
    fun `an inserted unchained line after the chain is reported`() {
        val log = AuditLog(file)
        repeat(2) { log.record("a", "write", "ok", "n$it.md") }
        val ls = lines()
        writeLines(listOf(ls[0], """{"ts":1,"agentId":"x","tool":"write","outcome":"ok"}""", ls[1]))
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.BROKEN, r.status)
        assertEquals(2L, r.brokenLine)
    }

    @Test
    fun `an extra key in a chained line is reported`() {
        val log = AuditLog(file)
        repeat(2) { log.record("a", "write", "ok", "n$it.md") }
        val ls = lines().toMutableList()
        ls[0] = ls[0].removeSuffix("}") + ""","approvedBy":"admin"}"""
        writeLines(ls)
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.BROKEN, r.status)
        assertEquals(1L, r.brokenLine)
        assertTrue("canonical" in r.reason!!, r.reason)
    }

    @Test
    fun `reformatting a chained line is reported`() {
        val log = AuditLog(file)
        repeat(2) { log.record("a", "write", "ok", "n$it.md") }
        val ls = lines().toMutableList()
        ls[1] = ls[1].replace(",", ", ")
        writeLines(ls)
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.BROKEN, r.status)
        assertEquals(2L, r.brokenLine)
    }

    @Test
    fun `hash-less lines added before the chain count as pre-chain, as documented`() {
        val log = AuditLog(file)
        log.record("a", "write", "ok", "n.md")
        val fake = """{"ts":1,"agentId":"x","tool":"write","outcome":"ok"}"""
        writeLines(listOf(fake) + lines())
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.OK, r.status)
        assertEquals(1, r.preChain)
    }

    @Test
    fun `an unterminated failing last line is reported as incomplete`() {
        val log = AuditLog(file)
        log.record("a", "write", "ok", "n.md")
        Files.writeString(file, Files.readString(file) + """{"ts":2,"agentId":"a","to""")
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.BROKEN, r.status)
        assertEquals(2L, r.brokenLine)
        assertTrue(r.reason!!.startsWith("incomplete last line"), r.reason)
    }

    @Test
    fun `an entry after a torn last line starts a new line and chains past it`() {
        val log = AuditLog(file)
        log.record("a", "write", "ok", "n.md")
        Files.writeString(file, Files.readString(file) + """{"ts":2,"agentId":"a","to""")
        log.record("a", "write", "ok", "m.md")
        val raw = Files.readAllLines(file)
        assertEquals(3, raw.size, raw.joinToString("\n"))
        assertEquals("m.md", entry(raw[2]).path)
        val broken = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.BROKEN, broken.status)
        assertEquals(2L, broken.brokenLine)
        // An operator deleting the torn line restores the chain.
        writeLines(listOf(raw[0], raw[2]))
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.OK, r.status, r.toString())
        assertEquals(2, r.chained)
    }

    @Test
    fun `a garbage line is reported`() {
        val log = AuditLog(file)
        log.record("a", "write", "ok", "n.md")
        Files.writeString(file, Files.readString(file) + "{not json\n")
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.BROKEN, r.status)
        assertEquals(2L, r.brokenLine)
        assertTrue("undecodable" in r.reason!!, r.reason)
    }

    @Test
    fun `verify on a missing file is absent and creates nothing`() {
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.ABSENT, r.status)
        assertNull(r.brokenLine)
        assertFalse(Files.exists(file.parent))
    }

    @Test
    fun `the last hash is found in a file larger than one read chunk`() {
        val log = AuditLog(file)
        val big = "x".repeat(20_000)
        log.record("a", "write", "ok", "n.md", detail = big)
        log.record("a", "write", "ok", "m.md", detail = big)
        log.record("a", "write", "ok", "o.md")
        val r = AuditLog.verify(file)
        assertEquals(AuditChainReport.Status.OK, r.status, r.toString())
        assertEquals(3, r.chained)
    }
}
