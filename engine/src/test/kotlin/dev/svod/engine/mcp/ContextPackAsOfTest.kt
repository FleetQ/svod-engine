package dev.svod.engine.mcp

import dev.svod.engine.index.SearchQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `context_pack` / `search` with `asOf`: answers come from the vault as committed at that time —
 * the note text and provenance of that commit, not of the current file.
 */
class ContextPackAsOfTest {

    private fun blocks(result: ToolResult): List<JsonObject> =
        (result.data["blocks"]?.jsonArray ?: error("no blocks in $result")).map { it.jsonObject }

    private fun iso(epochSeconds: Long) = java.time.Instant.ofEpochSecond(epochSeconds).toString()

    private class Seeded(val v1Commit: String, val v1Epoch: Long, val v2Commit: String)

    /** `topic.md` v1 (walrus), an unrelated commit, then v2 (pelican) — each more than a second apart. */
    private suspend fun McpFixture.seed(): Seeded {
        val r1 = tools.write(write, "topic.md", "# Topic\nthe codename is walrus", null)
        val rev = r1.data["revision"]!!.jsonPrimitive.content
        val v1 = engine.history("topic.md", 1).first()
        Thread.sleep(1100)
        tools.write(write, "other.md", "# Other\nunrelated note", null)
        Thread.sleep(1100)
        tools.write(write, "topic.md", "# Topic\nthe codename is pelican", rev)
        val v2 = engine.history("topic.md", 1).first()
        index.reconcileNow()
        return Seeded(v1.commit, v1.epochSeconds, v2.commit)
    }

    @Test
    fun `context_pack as of a time returns that commit's text and provenance`() {
        McpFixture().use { fx ->
            runBlocking {
                val s = fx.seed()
                val other = fx.engine.history("other.md", 1).first()
                // As of the unrelated commit: topic.md still holds v1, last touched by the v1 commit.
                val res = fx.tools.contextPack(fx.read, SearchQuery("codename", limit = 50), 4000, asOf = iso(other.epochSeconds))
                assertEquals("ok", res.status, "$res")
                val b = blocks(res).single { it["path"]!!.jsonPrimitive.content == "topic.md" }
                assertTrue(b["content"]!!.jsonPrimitive.content.contains("walrus"), "$b")
                assertTrue(!b["content"]!!.jsonPrimitive.content.contains("pelican"), "$b")
                assertEquals(s.v1Commit, b["commit"]!!.jsonPrimitive.content)
                assertEquals("Scribe", b["author"]!!.jsonPrimitive.content)

                val asOf = res.data["asOf"]!!.jsonObject
                assertEquals(iso(other.epochSeconds), asOf["requested"]!!.jsonPrimitive.content)
                assertEquals(other.commit, asOf["commit"]!!.jsonPrimitive.content)
                assertEquals(other.epochSeconds, asOf["commitEpochSeconds"]!!.jsonPrimitive.long)

                // Without asOf: the current text and the v2 commit, and no asOf object.
                val now = fx.tools.contextPack(fx.read, SearchQuery("codename", limit = 50), 4000)
                val nb = blocks(now).single { it["path"]!!.jsonPrimitive.content == "topic.md" }
                assertTrue(nb["content"]!!.jsonPrimitive.content.contains("pelican"))
                assertEquals(s.v2Commit, nb["commit"]!!.jsonPrimitive.content)
                assertNull(now.data["asOf"])
            }
        }
    }

    @Test
    fun `enumerate as of a time lists that commit's notes`() {
        McpFixture().use { fx ->
            runBlocking {
                val s = fx.seed()
                val res = fx.tools.contextPack(fx.read, SearchQuery(""), 4000, enumerate = true, asOf = iso(s.v1Epoch))
                assertEquals("ok", res.status, "$res")
                val bs = blocks(res)
                assertEquals(listOf("topic.md"), bs.map { it["path"]!!.jsonPrimitive.content })
                assertTrue(bs.single()["content"]!!.jsonPrimitive.content.contains("walrus"))
                assertEquals(s.v1Commit, bs.single()["commit"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `search tool with asOf reports the resolved commit`() {
        McpFixture().use { fx ->
            runBlocking {
                val s = fx.seed()
                val res = fx.tools.search(fx.read, SearchQuery("walrus"), asOf = iso(s.v1Epoch))
                assertEquals("ok", res.status, "$res")
                val hits = res.data["hits"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
                assertEquals(listOf("topic.md"), hits)
                assertEquals(s.v1Commit, res.data["asOf"]!!.jsonObject["commit"]!!.jsonPrimitive.content)

                val live = fx.tools.search(fx.read, SearchQuery("walrus"))
                assertTrue(live.data["hits"]!!.jsonArray.isEmpty())
                assertNull(live.data["asOf"])

                // A date means the end of that UTC day, so today's date resolves to HEAD.
                val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                val byDate = fx.tools.search(fx.read, SearchQuery("pelican"), asOf = today)
                assertEquals(s.v2Commit, byDate.data["asOf"]!!.jsonObject["commit"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `asOf rejects graphExpand and unparsable values`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seed()
                val q = SearchQuery("codename", limit = 50)
                val withGraph = fx.tools.contextPack(fx.read, q, 4000, graphExpand = true, asOf = "2026-01-01")
                assertEquals("bad_request", withGraph.status)
                assertEquals("bad_request", fx.tools.contextPack(fx.read, q, 4000, asOf = "last tuesday").status)
                assertEquals("bad_request", fx.tools.search(fx.read, q, asOf = "not-a-date").status)
            }
        }
    }
}
