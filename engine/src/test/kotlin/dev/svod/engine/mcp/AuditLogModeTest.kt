package dev.svod.engine.mcp

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The agent audit trail names agents and note paths: owner-only, also when an older build left it 0644. */
class AuditLogModeTest {
    private val ownerRw = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private fun posix(dir: java.nio.file.Path) = Files.getFileStore(dir).supportsFileAttributeView("posix")

    @Test
    fun `a fresh audit log is created 0600`() {
        val dir = Files.createTempDirectory("svod-audit-mode-")
        val file = dir.resolve("audit").resolve("audit.log")
        AuditLog(file).record("agent-1", "write", "ok", "n.md")
        assertTrue(Files.isRegularFile(file))
        if (posix(dir)) assertEquals(ownerRw, Files.getPosixFilePermissions(file))
    }

    @Test
    fun `an existing 0644 log is restricted on the first append and its lines are kept`() {
        val dir = Files.createTempDirectory("svod-audit-mode-")
        val file = dir.resolve("audit.log")
        Files.writeString(file, "{\"ts\":1,\"agentId\":\"old\",\"tool\":\"read\",\"outcome\":\"ok\"}\n")
        if (posix(dir)) Files.setPosixFilePermissions(file, ownerRw + PosixFilePermission.GROUP_READ + PosixFilePermission.OTHERS_READ)
        val log = AuditLog(file)
        log.record("agent-2", "write", "ok", "m.md")
        if (posix(dir)) assertEquals(ownerRw, Files.getPosixFilePermissions(file))
        val entries = log.entries()
        assertEquals(2, entries.size, entries.toString())
        assertEquals("old", entries[0].agentId); assertEquals("agent-2", entries[1].agentId)
    }
}
