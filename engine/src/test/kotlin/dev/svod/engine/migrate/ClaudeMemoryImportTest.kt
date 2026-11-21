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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeMemoryImportTest {

    private fun engineOn() = run {
        val vault = Files.createTempDirectory("svod-vault-")
        SvodEngine.open(vault, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    private fun write(root: Path, rel: String, text: String) {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
    }

    private val testing = "---\nname: testing-approach\ndescription: How tests are run\nmetadata:\n  type: feedback\n---\n" +
        "Run integration tests against a real database.\n"
    private val user = "---\nname: user-role\ndescription: Who the user is\ntype: User\n---\n" +
        "Backend engineer. See [[testing-approach]] and [[missing-memory]].\n"

    private fun memoryDir(): Path {
        val source = Files.createTempDirectory("claude-memory-")
        write(source, "feedback_testing.md", testing)
        write(source, "user_role.md", user)
        write(source, "MEMORY.md", "- [Testing](feedback_testing.md) — how tests run\n")
        return source
    }

    @Test
    fun `frontmatter is kept and the memory type becomes a tag`(): Unit = runBlocking {
        val source = memoryDir()
        write(source, "plain.md", "no frontmatter [[testing-approach]]\n")
        write(source, "tagged.md", "---\nname: tagged\ntags: [x]\nmetadata:\n  type: project\n---\nbody\n")
        write(source, "notype.md", "---\r\nname: notype\r\n---\r\nbody\r\n")
        engineOn().use { engine ->
            val r = ClaudeMemoryImport.import(source, engine)
            assertEquals(
                listOf(
                    "claude-memory/feedback_testing.md", "claude-memory/notype.md", "claude-memory/plain.md",
                    "claude-memory/tagged.md", "claude-memory/user_role.md",
                ),
                r.imported,
            )
            assertEquals(
                "---\nname: testing-approach\ndescription: How tests are run\nmetadata:\n  type: feedback\n" +
                    "tags: [claude-memory, feedback]\n---\nRun integration tests against a real database.\n",
                engine.read("claude-memory/feedback_testing.md")!!.text,
            )
            assertTrue("type: User\ntags: [claude-memory, user]\n---\n" in engine.read("claude-memory/user_role.md")!!.text)
            assertEquals("---\r\nname: notype\r\ntags: [claude-memory]\r\n---\r\nbody\r\n", engine.read("claude-memory/notype.md")!!.text)
            assertEquals(Files.readString(source.resolve("tagged.md")), engine.read("claude-memory/tagged.md")!!.text)
            assertTrue(engine.read("claude-memory/plain.md")!!.text.startsWith("no frontmatter "))
        }
    }

    @Test
    fun `name links to another memory become path links`(): Unit = runBlocking {
        val source = memoryDir()
        engineOn().use { engine ->
            ClaudeMemoryImport.import(source, engine)
            val text = engine.read("claude-memory/user_role.md")!!.text
            assertTrue("See [[claude-memory/feedback_testing|testing-approach]] and [[missing-memory]]." in text, text)

            val notes = engine.list().filter { it.endsWith(".md") }.associateWith { engine.read(it)!!.text }
            val out = LinkGraph.build(notes).outlinks("claude-memory/user_role.md").associate { it.target to it.resolvedPath }
            assertEquals("claude-memory/feedback_testing.md", out["claude-memory/feedback_testing"])
        }
    }

    @Test
    fun `MEMORY md index is not imported`(): Unit = runBlocking {
        val source = memoryDir()
        write(source, "sub/MEMORY.md", "---\nname: nested\n---\nnested\n")
        engineOn().use { engine ->
            val r = ClaudeMemoryImport.import(source, engine)
            val all = r.imported + r.unchanged + r.skipped
            assertTrue("claude-memory/MEMORY.md" !in all, all.toString())
            assertNull(engine.read("claude-memory/MEMORY.md"))
            assertTrue("claude-memory/sub/MEMORY.md" in r.imported, r.imported.toString())
        }
    }

    @Test
    fun `MEMORY md index is recognised for a relative or dot source`(): Unit = runBlocking {
        // `import-claude-memory cfg.json .` run from inside the memory folder: "." normalizes to the
        // empty path and the walk yields a bare "MEMORY.md", whose parent is null.
        val dot = Path.of(".").normalize()
        assertTrue(ClaudeMemoryImport.isTopLevelIndex(dot, dot.resolve("MEMORY.md")))
        assertTrue(!ClaudeMemoryImport.isTopLevelIndex(dot, dot.resolve("sub/MEMORY.md")))
        assertTrue(!ClaudeMemoryImport.isTopLevelIndex(dot, dot.resolve("user_role.md")))

        // A relative source path given to the full import still drops the index.
        val source = memoryDir()
        val relative = Path.of("").toAbsolutePath().relativize(source)
        engineOn().use { engine ->
            val r = ClaudeMemoryImport.import(relative, engine)
            assertEquals(listOf("claude-memory/feedback_testing.md", "claude-memory/user_role.md"), r.imported)
        }
    }

    @Test
    fun `re-import is idempotent`(): Unit = runBlocking {
        val source = memoryDir()
        engineOn().use { engine ->
            val first = ClaudeMemoryImport.import(source, engine)
            val head = engine.head()
            val second = ClaudeMemoryImport.import(source, engine)
            assertTrue(second.imported.isEmpty() && second.skipped.isEmpty())
            assertEquals(first.imported, second.unchanged)
            assertEquals(head, engine.head(), "no new commits on a no-op re-import")
        }
    }

    @Test
    fun `a locally edited note is not overwritten`(): Unit = runBlocking {
        val source = memoryDir()
        engineOn().use { engine ->
            ClaudeMemoryImport.import(source, engine)
            val rev = engine.read("claude-memory/feedback_testing.md")!!.revision
            engine.write("claude-memory/feedback_testing.md", "# edited\n", rev, Author("me", "me@x"))
            val again = ClaudeMemoryImport.import(source, engine)
            assertEquals(listOf("claude-memory/feedback_testing.md"), again.skipped)
            assertEquals("# edited\n", engine.read("claude-memory/feedback_testing.md")!!.text)
        }
    }

    @Test
    fun `into overrides the default folder`(): Unit = runBlocking {
        val source = memoryDir()
        engineOn().use { engine ->
            val r = ClaudeMemoryImport.import(source, engine, into = "mem/claude")
            assertEquals(listOf("mem/claude/feedback_testing.md", "mem/claude/user_role.md"), r.imported)
            val text = engine.read("mem/claude/user_role.md")!!.text
            assertTrue("[[mem/claude/feedback_testing|testing-approach]]" in text, text)
        }
    }
}
