package dev.svod.engine.memory

import dev.svod.engine.graphrag.SummaryLlm
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryExtractorTest {

    @Test
    fun `parses a plain JSON array`() {
        val out = LlmMemoryExtractor.parseCandidates(
            """[{"content": "Deploys happen on Tuesdays", "type": "policy", "subject": "deploy"},
                {"content": "Prefers short answers", "type": "preference"}]""",
            10,
        )
        assertEquals(
            listOf(
                ExtractedMemory("Deploys happen on Tuesdays", "policy", "deploy"),
                ExtractedMemory("Prefers short answers", "preference", null),
            ),
            out,
        )
    }

    @Test
    fun `parses an array wrapped in json code fences with surrounding prose`() {
        val raw = "Here are the memories:\n```json\n[{\"content\": \"The API runs on port 8080\", \"type\": \"fact\"}]\n```\nDone."
        assertEquals(listOf(ExtractedMemory("The API runs on port 8080", "fact")), LlmMemoryExtractor.parseCandidates(raw, 5))
    }

    @Test
    fun `drops blank content and maps unknown or missing type to fact`() {
        val out = LlmMemoryExtractor.parseCandidates(
            """[{"content": "   ", "type": "fact"}, {"type": "policy"},
                {"content": "A", "type": "rumour"}, {"content": "B"}, {"content": "C", "type": "EPISODE", "subject": " "}]""",
            10,
        )!!
        assertEquals(listOf("A" to "fact", "B" to "fact", "C" to "episode"), out.map { it.content to it.type })
        assertNull(out.last().subject, "a blank subject is dropped")
    }

    @Test
    fun `procedure is a recognised type, not coerced to fact`() {
        val out = LlmMemoryExtractor.parseCandidates(
            """[{"content": "1. Run tests\n2. Deploy", "type": "procedure", "subject": "deploy-api"}]""",
            5,
        )
        assertEquals(listOf(ExtractedMemory("1. Run tests\n2. Deploy", "procedure", "deploy-api")), out)
        assertTrue("procedure" in MemoryExtractor.TYPES)
        assertTrue("\"procedure\"" in LlmMemoryExtractor.systemPrompt(5), "the prompt offers the type")
    }

    @Test
    fun `caps the result at maxMemories`() {
        val raw = (1..8).joinToString(",", "[", "]") { """{"content": "m$it", "type": "fact"}""" }
        assertEquals(listOf("m1", "m2", "m3"), LlmMemoryExtractor.parseCandidates(raw, 3)!!.map { it.content })
    }

    @Test
    fun `unparseable output is null, an empty array is empty`() {
        assertNull(LlmMemoryExtractor.parseCandidates("I could not find anything.", 5))
        assertNull(LlmMemoryExtractor.parseCandidates("[not json at all", 5))
        assertEquals(emptyList(), LlmMemoryExtractor.parseCandidates("[]", 5))
    }

    @Test
    fun `extract sends the system prompt separately and returns null when the model is unavailable`(): Unit = runBlocking {
        var seenSystem: String? = null
        var seenPrompt: String? = null
        val llm = object : SummaryLlm {
            override val provider = "fake"
            override val model = "fake"
            override val isActive = true
            var reply: String? = """[{"content": "x", "type": "fact"}]"""
            override suspend fun summarise(prompt: String, system: String?): String? {
                seenPrompt = prompt; seenSystem = system
                return reply
            }
        }
        val ex = LlmMemoryExtractor(llm)
        assertTrue(ex.isActive)
        assertEquals(listOf(ExtractedMemory("x", "fact")), ex.extract("the transcript", 4))
        assertEquals("the transcript", seenPrompt)
        assertNotNull(seenSystem)
        assertTrue("JSON array" in seenSystem!!)
        llm.reply = null
        assertNull(ex.extract("the transcript", 4))
    }

    @Test
    fun `none extractor is inactive and yields null`(): Unit = runBlocking {
        assertEquals(false, NoneMemoryExtractor.isActive)
        assertNull(NoneMemoryExtractor.extract("anything", 5))
        assertEquals(NoneMemoryExtractor, MemoryExtractors.create(MemoryExtractionConfig()))
    }
}
