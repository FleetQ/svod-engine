package dev.svod.engine.migrate

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.index.MarkdownChunker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Mem0ImportTest {

    private fun engineOn() = run {
        val vault = Files.createTempDirectory("svod-vault-")
        SvodEngine.open(vault, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    private fun export(json: String): Path {
        val dir = Files.createTempDirectory("mem0-export-")
        val f = dir.resolve("export.json")
        Files.write(f, json.toByteArray(UTF_8))
        return f
    }

    private val sample = """
        {"results":[{
          "id":"a1b2-c3",
          "memory":"Prefers window seats\nwhen flying long-haul",
          "created_at":"2026-01-02T10:00:00Z",
          "updated_at":"2026-01-03T11:00:00Z",
          "user_id":"alice",
          "agent_id":"travel-bot",
          "categories":["food","travel plans"],
          "metadata":{"z":1,"a":{"nested":[true,"x"]}}
        }]}
    """.trimIndent()

    @Test
    fun `maps a memory to one note with frontmatter and tags`() = runBlocking {
        val src = export(sample)
        engineOn().use { engine ->
            val r = Mem0Import.import(src, engine)
            assertEquals(listOf("mem0/a1b2-c3.md"), r.imported)
            assertTrue(r.unchanged.isEmpty() && r.skipped.isEmpty(), r.toString())

            val note = assertNotNull(engine.read("mem0/a1b2-c3.md"))
            val doc = MarkdownChunker.parse(note.text)
            assertEquals("Prefers window seats", doc.frontmatter["title"])
            assertEquals("mem0", doc.frontmatter["source"])
            assertEquals("a1b2-c3", doc.frontmatter["mem0_id"])
            assertEquals("alice", doc.frontmatter["mem0_user_id"])
            assertEquals("travel-bot", doc.frontmatter["mem0_agent_id"])
            assertTrue("mem0_app_id" !in doc.frontmatter && "mem0_run_id" !in doc.frontmatter)
            assertEquals(listOf("mem0", "food", "travel-plans"), doc.frontmatter["tags"])
            assertTrue(doc.tags.containsAll(listOf("mem0", "food", "travel-plans")), doc.tags.toString())
            @Suppress("UNCHECKED_CAST")
            val meta = doc.frontmatter["mem0_metadata"] as Map<String, Any?>
            assertEquals(listOf("z", "a"), meta.keys.toList(), "metadata key order kept")
            assertEquals(1, meta["z"])
            assertEquals(mapOf("nested" to listOf(true, "x")), meta["a"])
            assertTrue(note.text.endsWith("---\nPrefers window seats\nwhen flying long-haul\n"), note.text)
        }
    }

    @Test
    fun `re-import is idempotent by mem0 id`() = runBlocking {
        val src = export(sample)
        engineOn().use { engine ->
            assertEquals(listOf("mem0/a1b2-c3.md"), Mem0Import.import(src, engine).imported)
            val head = engine.head()
            val before = engine.read("mem0/a1b2-c3.md")!!.text

            val second = Mem0Import.import(src, engine)
            assertEquals(listOf("mem0/a1b2-c3.md"), second.unchanged)
            assertTrue(second.imported.isEmpty() && second.skipped.isEmpty(), second.toString())
            assertEquals(head, engine.head(), "no commit on a no-op re-import")

            // the same id with changed text: reported skipped, never overwritten
            val changed = export(sample.replace("Prefers window seats", "Prefers aisle seats"))
            val third = Mem0Import.import(changed, engine)
            assertEquals(listOf("mem0/a1b2-c3.md"), third.skipped)
            assertTrue(third.imported.isEmpty() && third.unchanged.isEmpty())
            assertEquals(before, engine.read("mem0/a1b2-c3.md")!!.text)
            assertEquals(head, engine.head())
        }
    }

    @Test
    fun `malformed entries are reported as skipped and do not abort the import`() = runBlocking {
        val src = export(
            """
            [
              {"id":"good","memory":"ok"},
              {"memory":"no id"},
              {"id":"blank","memory":"   "},
              42,
              {"id":"../x","memory":"escape"},
              {"id":"good","memory":"again"}
            ]
            """.trimIndent()
        )
        engineOn().use { engine ->
            val r = Mem0Import.import(src, engine)
            assertEquals(listOf("mem0/good.md"), r.imported)
            assertTrue(r.skipped.any { it.startsWith("entry 1:") }, r.skipped.toString())
            assertTrue(r.skipped.any { it.startsWith("entry 2:") }, r.skipped.toString())
            assertTrue(r.skipped.any { it.startsWith("entry 3:") }, r.skipped.toString())
            assertTrue("entry 4: unsafe id" in r.skipped, r.skipped.toString())
            assertTrue("entry 5: duplicate id" in r.skipped, r.skipped.toString())
            assertEquals(5, r.skipped.size, r.skipped.toString())
            assertTrue(engine.read("mem0/good.md")!!.text.endsWith("---\nok\n"), "the first of the duplicates wins")
            assertTrue(engine.list().none { it.contains("..") || it == "x.md" })
        }
    }

    @Test
    fun `cyrillic memory text and categories round-trip`() = runBlocking {
        val src = export("""{"results":[{"id":"ru1","memory":"Обича зелен чай","categories":["напитки"]}]}""")
        engineOn().use { engine ->
            Mem0Import.import(src, engine)
            val text = engine.read("mem0/ru1.md")!!.text
            assertTrue("Обича зелен чай" in text, text)
            assertTrue("\\u" !in text, "YAML is not escaped: $text")
            assertTrue(text.endsWith("---\nОбича зелен чай\n"))
            assertContentEquals(text.toByteArray(UTF_8), engine.readBytes("mem0/ru1.md"))
            val doc = MarkdownChunker.parse(text)
            assertTrue("напитки" in doc.tags, doc.tags.toString())
            assertEquals("Обича зелен чай", doc.frontmatter["title"])
        }
    }

    @Test
    fun `accepts a bare array and a memories wrapper, rejects anything else`(): Unit = runBlocking {
        engineOn().use { engine ->
            val a = Mem0Import.import(export("""[{"id":"arr","memory":"from array"}]"""), engine)
            assertEquals(listOf("mem0/arr.md"), a.imported)
            val m = Mem0Import.import(export("""{"memories":[{"id":"wrap","memory":"from wrapper"}]}"""), engine, into = "imports/m0/")
            assertEquals(listOf("imports/m0/wrap.md"), m.imported)
            val root = Mem0Import.import(export("""[{"id":"top","memory":"at root"}]"""), engine, author = Author("t", "t@x"), into = "")
            assertEquals(listOf("top.md"), root.imported)

            assertFailsWith<IllegalArgumentException> { Mem0Import.import(export("{not json"), engine) }
            assertFailsWith<IllegalArgumentException> { Mem0Import.import(export("""{"items":[]}"""), engine) }
            assertFailsWith<IllegalArgumentException> { Mem0Import.import(export("\"just a string\""), engine) }
        }
    }
}
