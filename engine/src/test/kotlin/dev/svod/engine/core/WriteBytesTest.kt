package dev.svod.engine.core

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

class WriteBytesTest {

    private fun engineOn() = SvodEngine.open(Files.createTempDirectory("svod-bytes-"), CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun `writeBytes round-trips arbitrary non-UTF8 bytes and commits`() = runBlocking {
        engineOn().use { engine ->
            val author = Author("import", "i@x")
            // bytes that are NOT valid UTF-8 — a text path would corrupt these
            val data = byteArrayOf(0x00, 0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 'P'.code.toByte(), 0x01)
            val outcome = engine.writeBytes("assets/blob.bin", data, null, author)
            assertTrue(outcome is WriteOutcome.Success)
            assertContentEquals(data, engine.readBytes("assets/blob.bin"), "bytes survive verbatim")
            // committed with one commit; tree is consistent
            assertNotNull(engine.head())
            assertTrue(engine.list().contains("assets/blob.bin"))
        }
    }

    @Test
    fun `write(String) behaves as before alongside writeBytes`() = runBlocking {
        engineOn().use { engine ->
            val author = Author("ui", "ui@x")
            val ok = engine.write("note.md", "# Hi\nтекст", null, author)
            assertTrue(ok is WriteOutcome.Success)
            assertEquals("# Hi\nтекст", engine.read("note.md")!!.text)
        }
    }
}
