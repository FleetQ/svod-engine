package dev.svod.engine.mcp

import dev.svod.engine.events.EventTypes
import dev.svod.engine.index.SearchFilters
import dev.svod.engine.index.SearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MCP-surface guardrails: private content never leaks through context_pack; commit.created carries agentId. */
class RecallGuardsTest {

    @Test
    fun `context_pack never returns private content on any path`() = runBlocking {
        McpFixture().use { fx ->
            fx.engine.write("notes/pub.md", "# Pub\ncommon visible <private>LEAK_SPAN_XYZ</private> tail", null, fx.write.author)
            fx.engine.write("notes/whole.md", "---\nprivate: true\n---\n# Whole\ncommon LEAK_WHOLE_ABC", null, fx.write.author)
            fx.index.waitIdle()

            // Path B (ranked search): the public note comes back, but the span content is stripped and the
            // wholly-private note is absent entirely.
            val ranked = fx.tools.contextPack(fx.write, SearchQuery("common"), tokenBudget = 10_000)
            val rankedBlocks = ranked.data["blocks"]!!.jsonArray
            val rankedPaths = rankedBlocks.map { it.jsonObject["path"]!!.jsonPrimitive.content }.toSet()
            assertTrue("notes/pub.md" in rankedPaths, "the non-private note is recalled")
            assertTrue("notes/whole.md" !in rankedPaths, "a private:true note is never recalled")
            assertTrue(rankedBlocks.none { it.jsonObject["content"]!!.jsonPrimitive.content.contains("LEAK") },
                "no context_pack block leaks private content")

            // Path A (enumerate by prefix): same guarantee.
            val enumd = fx.tools.contextPack(fx.write, SearchQuery("", SearchFilters(pathPrefix = "notes/")), tokenBudget = 1, enumerate = true)
            val enumBlocks = enumd.data["blocks"]!!.jsonArray
            assertTrue(enumBlocks.none { it.jsonObject["path"]!!.jsonPrimitive.content == "notes/whole.md" }, "private:true excluded from enumerate")
            assertTrue(enumBlocks.none { it.jsonObject["content"]!!.jsonPrimitive.content.contains("LEAK") }, "enumerate strips private spans")

            // And the raw search tool likewise cannot surface the span or the private note.
            val hit = fx.tools.search(fx.write, SearchQuery("LEAK_SPAN_XYZ"))
            assertTrue(hit.data["hits"]!!.jsonArray.isEmpty(), "private span is unsearchable")
        }
    }

    @Test
    fun `captured session transcripts never appear in recall on any path`() = runBlocking {
        McpFixture().use { fx ->
            // A raw session transcript in the recall quarantine, carrying a unique needle...
            fx.engine.write(
                "messy/sessions/1700-proj-abcd1234.md",
                "---\ntype: session\nsessionId: abcd1234\n---\n# T\nSESSION_NEEDLE_XYZ raw transcript",
                null, fx.write.author,
            )
            // ...and a normal note carrying the same needle, so recall has real content to find.
            fx.engine.write("notes/pub.md", "# Pub\nSESSION_NEEDLE_XYZ appears in a real note", null, fx.write.author)
            fx.index.waitIdle()

            // Default recall (context_pack): the real note is recalled, the transcript never is.
            val ranked = fx.tools.contextPack(fx.write, SearchQuery("SESSION_NEEDLE_XYZ"), tokenBudget = 10_000)
            val paths = ranked.data["blocks"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }.toSet()
            assertTrue("notes/pub.md" in paths, "the real note is recalled")
            assertTrue(paths.none { it.startsWith("messy/sessions/") }, "session transcripts are never recalled (default)")

            // includeAll must NOT surface them — the exclusion has no includeAll escape.
            val all = fx.tools.search(fx.write, SearchQuery("SESSION_NEEDLE_XYZ", SearchFilters(includeAll = true)))
            assertTrue(
                all.data["hits"]!!.jsonArray.none { it.jsonObject["path"]!!.jsonPrimitive.content.startsWith("messy/sessions/") },
                "includeAll does not surface session transcripts",
            )

            // Browsing straight into the prefix is empty (no prefix-browse escape).
            val browse = fx.tools.search(fx.write, SearchQuery("SESSION_NEEDLE_XYZ", SearchFilters(pathPrefix = "messy/sessions/")))
            assertTrue(browse.data["hits"]!!.jsonArray.isEmpty(), "prefix-browse into messy/sessions/ is empty")

            // Path A enumerate by the prefix is likewise empty.
            val enumd = fx.tools.contextPack(fx.write, SearchQuery("", SearchFilters(pathPrefix = "messy/sessions/")), tokenBudget = 1, enumerate = true)
            assertTrue(enumd.data["blocks"]!!.jsonArray.isEmpty(), "enumerate into messy/sessions/ is empty")
        }
    }

    @Test
    fun `commit_created carries agentId`() = runBlocking {
        McpFixture().use { fx ->
            val payload = async(Dispatchers.Default) {
                fx.eventBus.events.first { it.type == EventTypes.COMMIT_CREATED }.data
            }
            delay(150) // let the collector subscribe before the write publishes (replay=0 SharedFlow)
            fx.tools.write(fx.write, "notes/a.md", "hello", null)
            val data = withTimeout(3_000) { payload.await() }
            assertEquals("scribe", data["agentId"]!!.jsonPrimitive.content, "commit.created includes the agent id")
        }
    }
}
