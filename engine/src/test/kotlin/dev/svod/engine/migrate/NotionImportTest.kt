package dev.svod.engine.migrate

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.graph.LinkGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotionImportTest {

    private fun engineOn() = run {
        val vault = Files.createTempDirectory("svod-vault-")
        SvodEngine.open(vault, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    private fun write(root: Path, rel: String, text: String) {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
    }

    private val wikiId = "0123456789abcdef0123456789abcdef"
    private val decId = "89abcdef0123456789abcdef01234567"
    private val taskId = "fedcba9876543210fedcba9876543210"
    private val rowId = "00112233445566778899aabbccddeeff"
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0x00, 0xFF.toByte())

    private val wikiText = "# Team Wiki\n\n" +
        "See [Decisions](Team%20Wiki%20$wikiId/Decisions%20$decId.md#block-1) and " +
        "[](Team%20Wiki%20$wikiId/Decisions%20$decId.md).\n" +
        "![](Team%20Wiki%20$wikiId/diagram.png)\n" +
        "[Tasks](Team%20Wiki%20$wikiId/Tasks%20$taskId.csv) [web](https://example.com/a%20b) [top](#top)\n"
    private val decText = "# Decisions\n\nBack to [Team Wiki](../Team%20Wiki%20$wikiId.md)\n"

    private fun notionExport(): Path {
        val source = Files.createTempDirectory("notion-export-")
        write(source, "Team Wiki $wikiId.md", wikiText)
        write(source, "Team Wiki $wikiId/Decisions $decId.md", decText)
        Files.write(source.resolve("Team Wiki $wikiId/diagram.png"), png)
        write(source, "Team Wiki $wikiId/Tasks $taskId.csv", "Name,Status\nRow One,Done\n")
        write(source, "Team Wiki $wikiId/Tasks ${taskId}_all.csv", "Name,Status\nRow One,Done\n")
        write(source, "Team Wiki $wikiId/Tasks $taskId/Row One $rowId.md", "# Row One\n\nStatus: Done\n")
        return source
    }

    @Test
    fun `ids are stripped from file and folder names and csv files are skipped`(): Unit = runBlocking {
        val source = notionExport()
        engineOn().use { engine ->
            val r = NotionImport.import(source, engine)
            assertEquals(
                listOf(
                    "notion/Team Wiki.md",
                    "notion/Team Wiki/Decisions.md",
                    "notion/Team Wiki/Tasks/Row One.md",
                    "notion/Team Wiki/diagram.png",
                ),
                r.imported,
            )
            assertEquals(
                listOf("notion/Team Wiki/Tasks $taskId" + "_all.csv", "notion/Team Wiki/Tasks.csv"),
                r.skipped,
            )
            assertTrue(engine.list().none { it.endsWith(".csv") })
            assertTrue(r.unchanged.isEmpty())
        }
    }

    @Test
    fun `relative page links become wikilinks and attachment links follow the renamed paths`(): Unit = runBlocking {
        val source = notionExport()
        engineOn().use { engine ->
            NotionImport.import(source, engine)
            val wiki = engine.read("notion/Team Wiki.md")!!.text
            val expected = "# Team Wiki\n\n" +
                "See [[notion/Team Wiki/Decisions|Decisions]] and [[notion/Team Wiki/Decisions]].\n" +
                "![](Team%20Wiki/diagram.png)\n" +
                "[Tasks](Team%20Wiki%20$wikiId/Tasks%20$taskId.csv) [web](https://example.com/a%20b) [top](#top)\n"
            assertEquals(expected, wiki)
            assertEquals(
                "# Decisions\n\nBack to [[notion/Team Wiki|Team Wiki]]\n",
                engine.read("notion/Team Wiki/Decisions.md")!!.text,
            )
            assertContentEquals(png, engine.readBytes("notion/Team Wiki/diagram.png"))

            val notes = engine.list().filter { it.endsWith(".md") }.associateWith { engine.read(it)!!.text }
            val graph = LinkGraph.build(notes)
            val out = graph.outlinks("notion/Team Wiki.md").associate { it.target to it.resolvedPath }
            assertEquals("notion/Team Wiki/Decisions.md", out["notion/Team Wiki/Decisions"])
            val back = graph.outlinks("notion/Team Wiki/Decisions.md").associate { it.target to it.resolvedPath }
            assertEquals("notion/Team Wiki.md", back["notion/Team Wiki"])
        }
    }

    @Test
    fun `colliding sibling names keep their ids while others are renamed`(): Unit = runBlocking {
        val idA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val idB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val idC = "cccccccccccccccccccccccccccccccc"
        val source = Files.createTempDirectory("notion-export-")
        write(source, "Meeting $idA.md", "# A\n[note](Meeting%20$idA/Notes%20$idC.md)\n")
        write(source, "Meeting $idB.md", "# B\n")
        write(source, "Meeting $idA/Notes $idC.md", "# notes A\n")
        write(source, "meeting $idB/Notes $idC.md", "# notes B\n")
        write(source, "Other $idC.md", "# other\n")
        engineOn().use { engine ->
            val r = NotionImport.import(source, engine, into = "n")
            assertEquals(
                listOf(
                    "n/Meeting $idA.md",
                    "n/Meeting $idA/Notes.md",
                    "n/Meeting $idB.md",
                    "n/Other.md",
                    "n/meeting $idB/Notes.md",
                ),
                r.imported,
            )
            assertEquals("# A\n[[n/Meeting $idA/Notes|note]]\n", engine.read("n/Meeting $idA.md")!!.text)
        }
    }

    @Test
    fun `re-import is unchanged and never overwrites a local edit`(): Unit = runBlocking {
        val source = notionExport()
        engineOn().use { engine ->
            NotionImport.import(source, engine)
            val head = engine.head()

            val second = NotionImport.import(source, engine)
            assertTrue(second.imported.isEmpty())
            assertEquals(
                listOf(
                    "notion/Team Wiki.md",
                    "notion/Team Wiki/Decisions.md",
                    "notion/Team Wiki/Tasks/Row One.md",
                    "notion/Team Wiki/diagram.png",
                ),
                second.unchanged,
            )
            assertEquals(head, engine.head(), "no new commits on a no-op re-import")

            val rev = engine.read("notion/Team Wiki.md")!!.revision
            engine.write("notion/Team Wiki.md", "# edited\n", rev, Author("me", "me@x"))
            val third = NotionImport.import(source, engine)
            assertTrue("notion/Team Wiki.md" in third.skipped, third.toString())
            assertTrue(third.imported.isEmpty())
            assertEquals("# edited\n", engine.read("notion/Team Wiki.md")!!.text)
        }
    }
}
