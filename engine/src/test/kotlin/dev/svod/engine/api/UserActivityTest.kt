package dev.svod.engine.api

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserActivityTest {

    @Test
    fun `touch is exact in memory, throttled on disk, and reloads from the file`() {
        val dir = Files.createTempDirectory("svod-activity-")
        val file = dir.resolve("user-activity.json")
        var now = 1_000_000L
        val a = UserActivity(file, clock = { now }, minIntervalMs = 60_000)
        assertNull(a.lastUsed("maria"))
        a.touch("maria")
        assertEquals(1_000_000L, a.lastUsed("maria"))
        assertTrue(Files.readString(file).contains("\"maria\":1000000"), Files.readString(file))
        now += 10_000
        a.touch("maria")
        assertEquals(1_010_000L, a.lastUsed("maria"), "memory is exact")
        assertTrue(Files.readString(file).contains("\"maria\":1000000"), "disk is throttled: ${Files.readString(file)}")
        now += 60_000
        a.touch("maria")
        assertTrue(Files.readString(file).contains("\"maria\":1070000"), Files.readString(file))
        assertEquals("1970-01-01T00:17:50Z", a.lastUsedIso("maria"))

        val reloaded = UserActivity(file)
        assertEquals(1_070_000L, reloaded.lastUsed("maria"))
        assertNull(reloaded.lastUsed("ivan"))
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
        }
    }

    @Test
    fun `an unwritable file is a warning, never a failed authentication`() {
        val a = UserActivity(java.nio.file.Paths.get("/nonexistent-svod-test/deeper/user-activity.json"))
        a.touch("maria")
        assertEquals(true, a.lastUsed("maria") != null, "memory still records it")
    }

    @Test
    fun `a corrupt file is ignored, not fatal`() {
        val file = Files.createTempDirectory("svod-activity-").resolve("user-activity.json")
        Files.writeString(file, "{ not json")
        assertNull(UserActivity(file).lastUsed("maria"))
    }
}
