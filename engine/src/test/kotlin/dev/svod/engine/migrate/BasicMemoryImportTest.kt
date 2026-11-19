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

class BasicMemoryImportTest {

    private fun engineOn() = run {
        val vault = Files.createTempDirectory("svod-vault-")
        SvodEngine.open(vault, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    private fun write(root: Path, rel: String, text: String) {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
    }

    private val project = "---\ntitle: Project Plan\npermalink: project-plan\n---\n# Project Plan\n\n" +
        "## Observations\n- [decision] Use Lucene for search #search\n\n" +
        "## Relations\n- implements [[Search Design]]\n- relates_to [[coffee-brewing-methods]]\n" +
        "- depends_on [[Search Design#Ranking|the ranking part]]\n- part_of [[Missing Thing]]\n"

    private fun basicMemoryProject(): Path {
        val source = Files.createTempDirectory("basic-memory-")
        // the filename differs from the title, so [[Search Design]] does not resolve by filename
        write(source, "specs/search-spec.md","---\ntitle: Search Design\npermalink: search-design\n---\n# Search Design\n## Ranking\nbm25\n")
        write(source, "notes/coffee.md", "---\ntitle: Coffee Brewing Methods\npermalink: coffee-brewing-methods\ntype: note\n---\n# Coffee\n")
        write(source, "notes/project.md", project)
        return source
    }

    @Test
    fun `relations to titles and permalinks become resolvable wikilinks, everything else stays verbatim`(): Unit = runBlocking {
        val source = basicMemoryProject()
        engineOn().use { engine ->
            val r = BasicMemoryImport.import(source, engine, into = "bm")
            assertEquals(listOf("bm/notes/coffee.md", "bm/notes/project.md", "bm/specs/search-spec.md"), r.imported)
            assertTrue(r.skipped.isEmpty() && r.unchanged.isEmpty())

            val text = engine.read("bm/notes/project.md")!!.text
            val expected = project
                .replace("[[Search Design]]", "[[bm/specs/search-spec|Search Design]]")
                .replace("[[coffee-brewing-methods]]", "[[bm/notes/coffee|coffee-brewing-methods]]")
                .replace("[[Search Design#Ranking|the ranking part]]", "[[bm/specs/search-spec#Ranking|the ranking part]]")
            assertEquals(expected, text)
            assertTrue("- [decision] Use Lucene for search #search" in text, "observation kept")
            assertTrue("[[Missing Thing]]" in text, "unmatched link left as-is")
            // untouched notes come across byte-identical
            assertEquals(
                Files.readString(source.resolve("notes/coffee.md")),
                engine.read("bm/notes/coffee.md")!!.text,
            )

            val notes = engine.list().filter { it.endsWith(".md") }.associateWith { engine.read(it)!!.text }
            val graph = LinkGraph.build(notes)
            val out = graph.outlinks("bm/notes/project.md").associate { it.target to it.resolvedPath }
            assertEquals("bm/specs/search-spec.md", out["bm/specs/search-spec"])
            assertEquals("bm/notes/coffee.md", out["bm/notes/coffee"])
            assertEquals("bm/specs/search-spec.md", out["bm/specs/search-spec#Ranking"])
        }
    }

    @Test
    fun `resolving links, ambiguous titles, attachments and dot-dirs are left alone`(): Unit = runBlocking {
        val source = Files.createTempDirectory("basic-memory-")
        write(source, "a/dup.md", "---\ntitle: Shared\n---\n# one\n")
        write(source, "b/other.md", "---\ntitle: Shared\n---\n# two\n")
        write(source, "c/Beta.md", "---\ntitle: Alpha\n---\n# beta\n")
        write(source, "c/Alpha Note.md", "---\ntitle: Beta\n---\n# alpha\n")
        write(source, "bad.md", "---\ntitle: [unclosed\n---\nsee [[Beta]] and [[Shared]] and [[dup]] ![[pic.png]]\n")
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0x00, 0xFF.toByte())
        Files.write(source.resolve("pic.png"), png)
        write(source, ".basic-memory/hidden.md", "---\ntitle: Hidden\n---\n")

        engineOn().use { engine ->
            val r = BasicMemoryImport.import(source, engine)
            assertEquals(listOf("a/dup.md", "b/other.md", "bad.md", "c/Alpha Note.md", "c/Beta.md", "pic.png"), r.imported)
            // [[Beta]] resolves by filename (c/Beta.md) even though another note is titled "Beta"
            assertEquals(Files.readString(source.resolve("bad.md")), engine.read("bad.md")!!.text)
            assertContentEquals(png, engine.readBytes("pic.png"))
            assertTrue(engine.list().none { it.contains(".basic-memory") })
        }
    }

    @Test
    fun `frontmatter stays verbatim, date-like permalinks match, table links keep the escaped pipe`(): Unit = runBlocking {
        val source = Files.createTempDirectory("basic-memory-")
        write(source, "daily/day.md", "---\ntitle: 2024-01-05\npermalink: 2024-01-05\n---\n# day\n")
        write(source, "odd/c#.md", "---\ntitle: Sharp\n---\n# sharp\n")
        val text = "---\ntitle: Index\nsee: \"[[2024-01-05]]\"\n---\n" +
            "- on [[2024-01-05]]\n| a | [[2024-01-05\\|that day]] |\n- uses [[Sharp]]\n"
        write(source, "index.md", text)

        engineOn().use { engine ->
            BasicMemoryImport.import(source, engine)
            val expected = "---\ntitle: Index\nsee: \"[[2024-01-05]]\"\n---\n" +
                "- on [[daily/day|2024-01-05]]\n| a | [[daily/day\\|that day]] |\n- uses [[Sharp]]\n"
            assertEquals(expected, engine.read("index.md")!!.text)
        }
    }

    @Test
    fun `re-import is unchanged and never clobbers a local edit`(): Unit = runBlocking {
        val source = basicMemoryProject()
        engineOn().use { engine ->
            BasicMemoryImport.import(source, engine)
            val head = engine.head()

            val second = BasicMemoryImport.import(source, engine)
            assertEquals(listOf("notes/coffee.md", "notes/project.md", "specs/search-spec.md"), second.unchanged)
            assertTrue(second.imported.isEmpty() && second.skipped.isEmpty())
            assertEquals(head, engine.head(), "no new commits on a no-op re-import")

            val rev = engine.read("notes/project.md")!!.revision
            engine.write("notes/project.md", "# edited\n", rev, Author("me", "me@x"))
            val third = BasicMemoryImport.import(source, engine)
            assertEquals(listOf("notes/project.md"), third.skipped)
            assertEquals("# edited\n", engine.read("notes/project.md")!!.text)
        }
    }
}
