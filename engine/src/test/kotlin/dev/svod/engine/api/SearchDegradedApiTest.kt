package dev.svod.engine.api

import dev.svod.engine.index.QueryFailingEmbedder
import dev.svod.engine.index.SearchMode
import dev.svod.engine.index.SearchQuery
import dev.svod.engine.mcp.McpFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `degraded` reaches the App API and the MCP tools (test plan A1, A2, M1). */
class SearchDegradedApiTest {

    private fun degradedOf(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject["degraded"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `A1 the search response always carries degraded, empty when the result is complete`() {
        ApiFixture.create().use { fx ->
            runBlocking { fx.engine.write("notes/a.md", "# Alpha\nquick fox", null, fx.writeAgent.author) }
            fx.index.waitIdle()
            val resp = fx.get("/api/v1/search?q=${ApiFixture.enc("quick")}")
            assertEquals(200, resp.statusCode())
            assertEquals(emptyList(), degradedOf(resp.body()))
        }
    }

    @Test
    fun `A1 A2 a failing query embed is reported on single-vault and across search`() {
        ApiFixture.create(QueryFailingEmbedder()).use { fx ->
            runBlocking { fx.engine.write("notes/a.md", "# Alpha\nquick fox", null, fx.writeAgent.author) }
            fx.index.waitIdle()
            val single = fx.get("/api/v1/search?q=${ApiFixture.enc("quick")}&mode=hybrid")
            assertEquals(200, single.statusCode())
            assertEquals(listOf("semantic"), degradedOf(single.body()))
            val hits = Json.parseToJsonElement(single.body()).jsonObject["hits"] as JsonArray
            assertTrue(hits.isNotEmpty(), "keyword hits still come back")

            val across = fx.get("/api/v1/search?q=${ApiFixture.enc("quick")}&mode=hybrid&across=true")
            assertEquals(200, across.statusCode())
            assertEquals(listOf("semantic"), degradedOf(across.body()))
        }
    }

    @Test
    fun `M1 MCP search and context_pack carry degraded`() = runBlocking {
        McpFixture(embedder = QueryFailingEmbedder()).use { fx ->
            fx.tools.write(fx.write, "a.md", "# A\nshared alpha topic", expectedRevision = null)
            fx.index.waitIdle()
            val search = fx.tools.search(fx.read, SearchQuery("shared", mode = SearchMode.HYBRID))
            assertEquals(listOf("semantic"), search.data["degraded"]!!.jsonArray.map { it.jsonPrimitive.content })
            val pack = fx.tools.contextPack(fx.read, SearchQuery("shared", mode = SearchMode.HYBRID), tokenBudget = 2000)
            assertEquals(listOf("semantic"), pack.data["degraded"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
        McpFixture().use { fx ->
            fx.tools.write(fx.write, "a.md", "# A\nshared alpha topic", expectedRevision = null)
            fx.index.waitIdle()
            val search = fx.tools.search(fx.read, SearchQuery("shared", mode = SearchMode.HYBRID))
            assertEquals(emptyList(), search.data["degraded"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }
}
