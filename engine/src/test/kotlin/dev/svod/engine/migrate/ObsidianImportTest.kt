package dev.svod.engine.migrate

import dev.svod.engine.core.SvodEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObsidianImportTest {

    @Test
    fun `imports markdown preserving frontmatter and links, skipping dot-dirs`() = runBlocking {
        val source = Files.createTempDirectory("obsidian-vault-")
        Files.createDirectories(source.resolve("notes/sub"))
        Files.writeString(source.resolve("notes/cat.md"), "---\ntags: [animal]\n---\n# Cat\nsee [[dog]]\n")
        Files.writeString(source.resolve("notes/sub/dog.md"), "# Dog\nwoof\n")
        // Obsidian config + an attachment that must be ignored
        Files.createDirectories(source.resolve(".obsidian"))
        Files.writeString(source.resolve(".obsidian/app.json"), "{}")
        Files.writeString(source.resolve(".obsidian/skip.md"), "# should be skipped")
        Files.writeString(source.resolve("notes/diagram.png"), "not markdown")

        val vault = Files.createTempDirectory("svod-vault-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        SvodEngine.open(vault, scope).use { engine ->
            val result = ObsidianImport.import(source, engine)

            assertEquals(listOf("notes/cat.md", "notes/sub/dog.md"), result.imported)
            assertTrue(result.skipped.isEmpty())

            val cat = engine.read("notes/cat.md")
            assertNotNull(cat)
            assertTrue("tags: [animal]" in cat.text, "frontmatter preserved")
            assertTrue("[[dog]]" in cat.text, "wikilink preserved")
            assertNotNull(engine.read("notes/sub/dog.md"))
            // dot-dir content and non-markdown never imported
            assertTrue(engine.list().none { it.contains(".obsidian") || it.endsWith(".png") })
        }
    }
}
