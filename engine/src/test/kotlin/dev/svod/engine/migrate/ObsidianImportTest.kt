package dev.svod.engine.migrate

import dev.svod.engine.core.SvodEngine
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

class ObsidianImportTest {

    private fun engineOn() = run {
        val vault = Files.createTempDirectory("svod-vault-")
        SvodEngine.open(vault, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    @Test
    fun `imports markdown and attachments, preserving frontmatter and links, skipping dot-dirs`() = runBlocking {
        val source = Files.createTempDirectory("obsidian-vault-")
        Files.createDirectories(source.resolve("notes/sub"))
        Files.writeString(source.resolve("notes/cat.md"), "---\ntags: [animal]\n---\n# Cat\nsee [[dog]] ![[diagram.png]]\n")
        Files.writeString(source.resolve("notes/sub/dog.md"), "# Dog\nwoof\n")
        // a real binary attachment (non-UTF-8 bytes) that must come across byte-for-byte
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x00, 0xFF.toByte(), 0xFE.toByte())
        Files.write(source.resolve("notes/diagram.png"), png)
        // Obsidian config must be skipped
        Files.createDirectories(source.resolve(".obsidian"))
        Files.writeString(source.resolve(".obsidian/app.json"), "{}")
        Files.writeString(source.resolve(".obsidian/skip.md"), "# should be skipped")

        engineOn().use { engine ->
            val result = ObsidianImport.import(source, engine)

            assertEquals(listOf("notes/cat.md", "notes/diagram.png", "notes/sub/dog.md"), result.imported)
            assertTrue(result.skipped.isEmpty() && result.unchanged.isEmpty())

            val cat = engine.read("notes/cat.md")
            assertNotNull(cat)
            assertTrue("tags: [animal]" in cat.text, "frontmatter preserved")
            assertTrue("[[dog]]" in cat.text && "![[diagram.png]]" in cat.text, "wikilink + attachment ref preserved")
            // attachment is present byte-for-byte (so ![[diagram.png]] resolves)
            assertContentEquals(png, engine.readBytes("notes/diagram.png"))
            // .obsidian/ never imported
            assertTrue(engine.list().none { it.contains(".obsidian") })
        }
    }

    @Test
    fun `re-import is idempotent — unchanged, no clobber, history stable`() = runBlocking {
        val source = Files.createTempDirectory("obsidian-vault-")
        Files.writeString(source.resolve("a.md"), "# A\nbody\n")
        Files.write(source.resolve("img.bin"), byteArrayOf(1, 2, 3, 4))

        engineOn().use { engine ->
            val first = ObsidianImport.import(source, engine)
            assertEquals(listOf("a.md", "img.bin"), first.imported)
            val headAfterFirst = engine.head()

            val second = ObsidianImport.import(source, engine)
            assertEquals(listOf("a.md", "img.bin"), second.unchanged, "second run sees everything unchanged")
            assertTrue(second.imported.isEmpty() && second.skipped.isEmpty())
            assertEquals(headAfterFirst, engine.head(), "no new commits on a no-op re-import")
            // no duplicate-name artifacts
            assertTrue(engine.list().none { it.contains("(1)") })
        }
    }

    @Test
    fun `re-import never clobbers a locally edited note`() = runBlocking {
        val source = Files.createTempDirectory("obsidian-vault-")
        Files.writeString(source.resolve("note.md"), "# original\n")

        engineOn().use { engine ->
            ObsidianImport.import(source, engine)
            // a local edit through the engine after the first import
            val rev = engine.read("note.md")!!.revision
            engine.write("note.md", "# locally edited\n", rev, dev.svod.engine.core.Author("me", "me@x"))

            val second = ObsidianImport.import(source, engine)
            assertEquals(listOf("note.md"), second.skipped, "differing file is skipped, not overwritten")
            assertEquals("# locally edited\n", engine.read("note.md")!!.text, "local edit preserved")
        }
    }

    @Test
    fun `imports cyrillic note and attachment names`() = runBlocking {
        val source = Files.createTempDirectory("obsidian-vault-")
        Files.createDirectories(source.resolve("бележки"))
        Files.writeString(source.resolve("бележки/идея.md"), "# Идея\nвиж [[друга]]\n")
        Files.write(source.resolve("бележки/снимка.png"), byteArrayOf(0x10, 0x20, 0x30))

        engineOn().use { engine ->
            val result = ObsidianImport.import(source, engine)
            assertEquals(listOf("бележки/идея.md", "бележки/снимка.png"), result.imported)
            assertTrue("[[друга]]" in engine.read("бележки/идея.md")!!.text)
            assertContentEquals(byteArrayOf(0x10, 0x20, 0x30), engine.readBytes("бележки/снимка.png"))
        }
    }
}
