package dev.svod.engine.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val ИВАН = Author("Иван", "ivan@svod.test")

/** UTF-8 / Cyrillic must work for filenames, paths and content (core.quotepath=false). */
class CyrillicTest {

    @Test
    fun `cyrillic filenames and content round-trip through git`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            val path = "заметки/привет мир.md"
            val content = "# Привет\nЭто проверка UTF-8: Ñ, ü, 日本語, emoji 🌍\n"

            val created = e.write(path, content, expectedRevision = null, author = ИВАН)
            assertTrue(created is WriteOutcome.Success, "got $created")

            val read = e.read(path)
            assertNotNull(read)
            assertEquals(content, read.text)

            // history preserves the Cyrillic author identity
            val hist = e.history(path)
            assertTrue(hist.isNotEmpty())
            assertEquals("Иван", hist.first().authorName)

            // git CLI agrees the path is stored unquoted (quotepath=false)
            val (code, out) = GitCli.run(fx.root, "ls-files")
            assertEquals(0, code)
            assertTrue(out.contains("заметки/привет мир.md"), "git ls-files should show raw UTF-8 path, got: $out")

            assertTrue(GitCli.fsckClean(fx.root))
        }
    }
}
