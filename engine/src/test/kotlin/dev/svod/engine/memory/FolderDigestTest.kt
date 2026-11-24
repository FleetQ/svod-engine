package dev.svod.engine.memory

import dev.svod.engine.core.Author
import dev.svod.engine.core.GitCli
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.VaultFixture
import dev.svod.engine.core.WriteOutcome
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `svod-engine digest`: generated index.md + log.md per folder, one commit, idempotent, never clobbers user files. */
class FolderDigestTest {

    private lateinit var fx: VaultFixture
    private lateinit var engine: SvodEngine

    private val alice = Author("alice", "alice@example.com")
    private val bob = Author("bob", "bob@example.com", agent = "claude-code")

    @BeforeTest
    fun setUp() {
        fx = VaultFixture.create()
        engine = fx.open()
    }

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun write(path: String, content: String, author: Author) {
        val out = engine.write(path, content, null, author)
        assertTrue(out is WriteOutcome.Success, "write $path: $out")
    }

    private fun logEntries(text: String) = text.lines().filter { it.startsWith("- ") }

    @Test
    fun `digest writes index and log in one commit`() = runBlocking {
        write("proj/a.md", "---\ntitle: Alpha plan\n---\nbody", alice)
        write("proj/sub/b.md", "# Beta heading\ntext", bob)
        val before = GitCli.commitCount(fx.root)

        val r = FolderDigest.run(engine, listOf("proj"))
        assertEquals(listOf("proj"), r.written)
        assertEquals(before + 1, GitCli.commitCount(fx.root))

        val head = engine.history("proj", 1).first()
        assertEquals("digest: proj", head.message.lines().first())
        assertEquals(head.commit, engine.history("proj/index.md", 1).first().commit)
        assertEquals(head.commit, engine.history("proj/log.md", 1).first().commit)

        val index = assertNotNull(engine.read("proj/index.md")).text
        assertTrue(index.startsWith("---\ngenerated: svod-folder-digest\n---"), index)
        assertTrue("[[proj/a|Alpha plan]]" in index, index)
        assertTrue("[[proj/sub/b|Beta heading]]" in index, index)
        assertTrue("alice" in index && "bob (agent claude-code)" in index, index)

        val log = assertNotNull(engine.read("proj/log.md")).text
        assertTrue(log.startsWith("---\ngenerated: svod-folder-digest\n---"), log)
        assertEquals(2, logEntries(log).size, log)
        assertTrue("(agent claude-code)" in logEntries(log)[0], log)
        assertTrue(GitCli.isWorkingTreeClean(fx.root))
    }

    @Test
    fun `second run without changes is a no-op and the digest commit is not logged`() = runBlocking {
        write("proj/a.md", "# A\n", alice)
        FolderDigest.run(engine, listOf("proj"))
        val head = engine.head()
        val log = engine.read("proj/log.md")!!.text
        assertTrue(logEntries(log).none { "digest:" in it }, log)

        val r = FolderDigest.run(engine, listOf("proj"))
        assertEquals(listOf("proj"), r.unchanged)
        assertTrue(r.written.isEmpty())
        assertEquals(head, engine.head())
    }

    @Test
    fun `a new note updates both files and adds one log entry`() = runBlocking {
        write("proj/a.md", "# A\n", alice)
        FolderDigest.run(engine, listOf("proj"))
        val log1 = engine.read("proj/log.md")!!.text
        Thread.sleep(1100)
        write("proj/c.md", "# Gamma\n", bob)

        val r = FolderDigest.run(engine, listOf("proj"))
        assertEquals(listOf("proj"), r.written)
        val log2 = engine.read("proj/log.md")!!.text
        assertEquals(logEntries(log1).size + 1, logEntries(log2).size, log2)
        assertTrue("bob (agent claude-code)" in logEntries(log2)[0], log2)
        assertTrue("[[proj/c|Gamma]]" in engine.read("proj/index.md")!!.text)
    }

    @Test
    fun `a user-written index is never overwritten`() = runBlocking {
        write("proj/a.md", "# A\n", alice)
        write("proj/index.md", "# My own index\n", alice)
        val head = engine.head()

        val r = FolderDigest.run(engine, listOf("proj"))
        assertTrue("proj" in r.skipped, r.toString())
        assertTrue(r.written.isEmpty())
        assertEquals(head, engine.head())
        assertEquals("# My own index\n", engine.read("proj/index.md")!!.text)
        assertNull(engine.read("proj/log.md"))
    }

    @Test
    fun `a user-written log is never overwritten`() = runBlocking {
        write("proj/a.md", "# A\n", alice)
        write("proj/log.md", "my own log\n", alice)
        val head = engine.head()

        val r = FolderDigest.run(engine, listOf("proj"))
        assertTrue("proj" in r.skipped, r.toString())
        assertEquals(head, engine.head())
        assertEquals("my own log\n", engine.read("proj/log.md")!!.text)
        assertNull(engine.read("proj/index.md"))
    }

    @Test
    fun `a folder with more commits than maxLog stays stable on re-run`() = runBlocking {
        write("proj/a.md", "# A\n", alice)
        write("proj/b.md", "# B\n", alice)
        write("proj/c.md", "# C\n", bob)

        assertEquals(listOf("proj"), FolderDigest.run(engine, listOf("proj"), maxLog = 2).written)
        val log1 = engine.read("proj/log.md")!!.text
        assertEquals(2, logEntries(log1).size, log1)
        val head = engine.head()

        repeat(2) {
            val r = FolderDigest.run(engine, listOf("proj"), maxLog = 2)
            assertEquals(listOf("proj"), r.unchanged, r.toString())
            assertEquals(head, engine.head())
        }
        assertEquals(log1, engine.read("proj/log.md")!!.text)
    }

    @Test
    fun `a commit that touched only the digest files is not logged`() = runBlocking {
        write("proj/a.md", "# A\n", alice)
        FolderDigest.run(engine, listOf("proj"))
        val log1 = engine.read("proj/log.md")!!.text
        val idx = engine.read("proj/index.md")!!
        val edited = engine.write("proj/index.md", idx.text + "\nhand note\n", idx.revision, alice)
        assertTrue(edited is WriteOutcome.Success, edited.toString())
        assertTrue(engine.history("proj/index.md", 1).first().message.lines().first().let { !it.startsWith("digest: ") })

        val r = FolderDigest.run(engine, listOf("proj"))
        assertEquals(listOf("proj"), r.written)
        assertEquals(log1, engine.read("proj/log.md")!!.text)
    }

    @Test
    fun `no folder arguments digests every top-level folder with notes`() = runBlocking {
        write("root.md", "# Root\n", alice)
        write("one/a.md", "# A\n", alice)
        write("two/deep/b.md", "# B\n", bob)

        val r = FolderDigest.run(engine)
        assertEquals(listOf("one", "two"), r.written.sorted())
        assertNotNull(engine.read("one/index.md"))
        assertNotNull(engine.read("two/log.md"))
        assertNull(engine.read("index.md"))
        assertNull(engine.read("log.md"))
    }
}
