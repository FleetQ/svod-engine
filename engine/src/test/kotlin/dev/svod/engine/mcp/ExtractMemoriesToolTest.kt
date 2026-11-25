package dev.svod.engine.mcp

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.MarkdownChunker
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.index.SearchQuery
import dev.svod.engine.memory.ExtractedMemory
import dev.svod.engine.memory.MemoryExtractor
import dev.svod.engine.security.SecretScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `extract_memories`: model-proposed memories through the `remember` gate, always provisional. */
class ExtractMemoriesToolTest {

    private class FakeExtractor(
        var result: List<ExtractedMemory>?,
        override val isActive: Boolean = true,
    ) : MemoryExtractor {
        var calls = 0
        var lastMax = -1
        override suspend fun extract(transcript: String, maxMemories: Int): List<ExtractedMemory>? {
            calls++; lastMax = maxMemories
            return result
        }
    }

    private class Fixture(val extractor: MemoryExtractor, maxTranscriptChars: Int = 20_000) : AutoCloseable {
        val root: Path = Files.createTempDirectory("svod-extract-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine: SvodEngine = SvodEngine.open(root, scope, SecretScanner(enabled = true))
        val index: IndexService = IndexService(root, root.resolve(".svod").resolve("index"), NoneEmbedder).start()
        val registry = AgentRegistry(listOf(WRITE_AGENT, READ_AGENT))
        val tools = SvodTools(
            engine, index, AuditLog(root.resolve(".svod").resolve("audit").resolve("audit.log")), RateLimiter.default(),
            requireReview = false, extractor = extractor, maxTranscriptChars = maxTranscriptChars,
        )

        init { engine.onCommit { index.onCommit(it) } }

        val write: AgentIdentity get() = registry.byAgentId("scribe")!!
        val read: AgentIdentity get() = registry.byAgentId("reader")!!

        suspend fun memoryNotes() = engine.list().filter { it.startsWith("memory/") }

        override fun close() { index.close(); engine.close() }
    }

    private fun str(r: ToolResult, k: String) = r.data[k]?.jsonPrimitive?.content

    private val transcript = "user: we deploy on Tuesdays only\nassistant: noted\nuser: and I like short answers"

    @Test
    fun `candidates are written provisional with the default source, even with review off`(): Unit = runBlocking {
        val fake = FakeExtractor(listOf(
            ExtractedMemory("Deploys happen on Tuesdays only.", "policy", "deploy"),
            ExtractedMemory("The user prefers short answers.", "preference"),
        ))
        Fixture(fake).use { fx ->
            val r = fx.tools.extractMemories(fx.write, transcript, null)
            assertEquals("ok", str(r, "status"), r.data.toString())
            assertEquals(2, r.data["extracted"]!!.jsonPrimitive.int)
            val memories = r.data["memories"]!!.jsonArray
            assertEquals(2, memories.size)
            for (m in memories) {
                val o = m.jsonObject
                assertEquals("written", o["status"]!!.jsonPrimitive.content)
                assertEquals("provisional", o["memoryStatus"]!!.jsonPrimitive.content)
                val parsed = MarkdownChunker.parse(fx.engine.read(o["path"]!!.jsonPrimitive.content)!!.text)
                assertEquals("provisional", parsed.status)
                assertEquals("extract_memories", parsed.frontmatter["source"])
            }
            assertEquals(10, fake.lastMax, "default maxMemories")

            // Provisional memories stay out of recall.
            fx.index.waitIdle()
            val hits = fx.index.search(SearchQuery("Tuesdays")).hits
            assertTrue(hits.none { it.path.startsWith("memory/") }, hits.toString())
        }
    }

    @Test
    fun `the caller's source is stored, maxMemories is clamped and a restatement dedups`(): Unit = runBlocking {
        val fake = FakeExtractor(listOf(ExtractedMemory("Deploys happen on Tuesdays only.", "fact")))
        Fixture(fake).use { fx ->
            val r1 = fx.tools.extractMemories(fx.write, transcript, "session-42", maxMemories = 99)
            assertEquals(20, fake.lastMax)
            val path = r1.data["memories"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content
            assertEquals("session-42", MarkdownChunker.parse(fx.engine.read(path)!!.text).frontmatter["source"])

            val r2 = fx.tools.extractMemories(fx.write, transcript, "session-42", maxMemories = 0)
            assertEquals(1, fake.lastMax)
            assertEquals("deduped", r2.data["memories"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
            assertEquals(1, fx.memoryNotes().size)
        }
    }

    @Test
    fun `an extracted procedure does not retire the approved procedure for its subject`(): Unit = runBlocking {
        val v1 = "1. Run the unit tests\n2. Build the docker image\n3. Push the image to the registry"
        val v2 = "1. Run the unit tests\n2. Build the docker image with cache\n3. Push the image to the registry\n4. Restart the api pods"
        val fake = FakeExtractor(listOf(ExtractedMemory(v2, "procedure", "deploy-api")))
        Fixture(fake).use { fx ->
            val first = fx.tools.remember(fx.write, v1, "procedure", "deploy-api", null, null, null, null, null)
            val firstPath = str(first, "path")!!
            assertEquals("active", str(first, "memoryStatus"))
            fx.index.waitIdle()

            val r = fx.tools.extractMemories(fx.write, transcript, null)
            val m = r.data["memories"]!!.jsonArray.single().jsonObject
            assertEquals("written", m["status"]!!.jsonPrimitive.content)
            assertEquals("provisional", m["memoryStatus"]!!.jsonPrimitive.content)
            assertTrue(m["superseded"] == null, m.toString())

            val old = MarkdownChunker.parse(fx.engine.read(firstPath)!!.text)
            assertEquals("active", old.status, "the approved procedure stays live")
            assertEquals(null, old.supersededBy)
        }
    }

    @Test
    fun `not configured is an error and writes nothing`(): Unit = runBlocking {
        Fixture(dev.svod.engine.memory.NoneMemoryExtractor).use { fx ->
            val r = fx.tools.extractMemories(fx.write, transcript, null)
            assertTrue(r.isError)
            assertTrue("not configured" in str(r, "message")!!, r.data.toString())
            assertEquals(emptyList(), fx.memoryNotes())
        }
    }

    @Test
    fun `model failure is an internal error and writes nothing`(): Unit = runBlocking {
        Fixture(FakeExtractor(null)).use { fx ->
            val r = fx.tools.extractMemories(fx.write, transcript, null)
            assertEquals("internal_error", r.status)
            assertEquals(emptyList(), fx.memoryNotes())
        }
    }

    @Test
    fun `blank, too long and secret transcripts are refused before the model is called`(): Unit = runBlocking {
        val fake = FakeExtractor(listOf(ExtractedMemory("x", "fact")))
        Fixture(fake, maxTranscriptChars = 100).use { fx ->
            assertEquals("bad_request", fx.tools.extractMemories(fx.write, "   ", null).status)
            assertEquals("bad_request", fx.tools.extractMemories(fx.write, "a".repeat(101), null).status)
            val secret = "user: here is the key\n-----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY-----"
            assertEquals("blocked", fx.tools.extractMemories(fx.write, secret, null).status)
            assertEquals(0, fake.calls, "the model never saw a refused transcript")
            assertEquals(emptyList(), fx.memoryNotes())
        }
    }

    @Test
    fun `a read-only agent is denied`(): Unit = runBlocking {
        val fake = FakeExtractor(listOf(ExtractedMemory("x", "fact")))
        Fixture(fake).use { fx ->
            val r = fx.tools.extractMemories(fx.read, transcript, null)
            assertEquals("denied", r.status)
            assertEquals(0, fake.calls)
            assertFalse(fx.memoryNotes().isNotEmpty())
        }
    }
}
