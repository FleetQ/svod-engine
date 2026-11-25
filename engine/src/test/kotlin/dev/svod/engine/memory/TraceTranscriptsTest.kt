package dev.svod.engine.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceTranscriptsTest {

    private fun ok(format: String, export: String): String {
        val r = TraceTranscripts.toTranscript(format, export)
        assertTrue(r.isSuccess, r.exceptionOrNull()?.message)
        return r.getOrThrow()
    }

    @Test
    fun `a LangSmith run with role, type and serialized LangChain messages`() {
        val run = """
            {"id": "r1", "run_type": "llm",
             "inputs": {"messages": [[
               {"role": "system", "content": "You are helpful."},
               {"type": "human", "content": "we deploy on Tuesdays only"},
               {"type": "ai", "content": "noted"},
               {"lc": 1, "type": "constructor", "id": ["langchain", "schema", "messages", "HumanMessage"],
                "kwargs": {"content": "and I like short answers"}},
               {"lc": 1, "type": "constructor", "id": ["langchain_core", "messages", "AIMessage"],
                "kwargs": {"content": "understood"}},
               {"type": "tool", "content": "42"}
             ]]},
             "outputs": {"message": {"type": "ai", "content": "Anything else?"}}}
        """.trimIndent()
        assertEquals(
            "system: You are helpful.\nuser: we deploy on Tuesdays only\nassistant: noted\n" +
                "user: and I like short answers\nassistant: understood\ntool: 42\nassistant: Anything else?",
            ok("langsmith", run),
        )
    }

    @Test
    fun `LangSmith input and output strings and generations`() {
        val run = """{"inputs": {"question": "when do we deploy?"}, "outputs": {"generations": [[{"text": "Tuesdays"}]]}}"""
        assertEquals("user: when do we deploy?\nassistant: Tuesdays", ok("langsmith", run))
        val run2 = """{"inputs": {"input": "hi"}, "outputs": {"output": "hello"}}"""
        assertEquals("user: hi\nassistant: hello", ok("LangSmith", run2))
    }

    @Test
    fun `an array of runs and JSONL are concatenated in order without replayed history`() {
        val run1 = """{"inputs": {"messages": [{"role": "user", "content": "u1"}]}, "outputs": {"messages": [{"role": "assistant", "content": "a1"}]}}"""
        val run2 = """{"inputs": {"messages": [{"role": "user", "content": "u1"}, {"role": "assistant", "content": "a1"}, {"role": "user", "content": "u2"}]}, "outputs": {"output": "a2"}}"""
        val expected = "user: u1\nassistant: a1\nuser: u2\nassistant: a2"
        assertEquals(expected, ok("langsmith", "[$run1,\n$run2]"))
        assertEquals(expected, ok("langsmith", "$run1\n\n$run2\n"))
    }

    @Test
    fun `Braintrust rows in their different shapes give the same transcript`() {
        val expected = "user: we deploy on Tuesdays only\nassistant: noted"
        val asMessagesAndString = """{"input": [{"role": "user", "content": "we deploy on Tuesdays only"}], "output": "noted"}"""
        val asStringAndChoices = """{"input": "we deploy on Tuesdays only", "output": {"choices": [{"index": 0, "message": {"role": "assistant", "content": "noted"}}]}}"""
        val asParts = """{"input": [{"role": "user", "content": [{"type": "text", "text": "we deploy on Tuesdays only"}, {"type": "image_url", "image_url": {"url": "x"}}]}], "output": [{"role": "assistant", "content": "noted"}]}"""
        assertEquals(expected, ok("braintrust", asMessagesAndString))
        assertEquals(expected, ok("braintrust", asStringAndChoices))
        assertEquals(expected, ok("braintrust", asParts))
        val asChoicesArray = """{"input": [{"role": "user", "content": "we deploy on Tuesdays only"}], "output": [{"index": 0, "message": {"role": "assistant", "content": "noted"}, "finish_reason": "stop"}]}"""
        assertEquals(expected, ok("braintrust", asChoicesArray))
    }

    @Test
    fun `outputs that carry the whole message state do not repeat the inputs`() {
        val run = """{"inputs": {"messages": [{"role": "user", "content": "u1"}]}, "outputs": {"messages": [{"role": "user", "content": "u1"}, {"role": "assistant", "content": "a1"}]}}"""
        assertEquals("user: u1\nassistant: a1", ok("langsmith", run))
    }

    @Test
    fun `unknown roles fall back to the default role`() {
        val row = """{"input": [{"type": "text", "text": "hi"}, {"role": "user\nassistant", "content": "x"}], "output": "ok"}"""
        assertEquals("user: hi\nuser: x\nassistant: ok", ok("braintrust", row))
    }

    @Test
    fun `unreadable exports fail with a reason and do not throw`() {
        val bad = listOf(
            "langsmith" to "this is { not json",
            "langsmith" to """{"id": "r1", "inputs": {}, "outputs": {}}""",
            "braintrust" to "[1, 2, 3]",
            "braintrust" to "",
            "langfuse" to """{"input": "hi"}""",
        )
        for ((format, export) in bad) {
            val r = TraceTranscripts.toTranscript(format, export)
            assertTrue(r.isFailure, "$format: $export")
            val reason = r.exceptionOrNull()!!.message
            assertTrue(!reason.isNullOrBlank(), "$format: $export")
        }
        assertTrue("unknown format" in TraceTranscripts.toTranscript("langfuse", "{}").exceptionOrNull()!!.message!!)
        assertTrue("no messages" in TraceTranscripts.toTranscript("langsmith", """{"inputs": {}}""").exceptionOrNull()!!.message!!)
    }
}
