package dev.svod.engine.api

import dev.svod.engine.core.Author
import dev.svod.engine.lifecycle.ApiCompatibility
import dev.svod.engine.mcp.AgentRegistry
import dev.svod.engine.mcp.AgentRole
import dev.svod.engine.mcp.McpFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0020 decision 4: who created / last changed a note, from git only. */
class AttributionTest {

    private val nikola = AgentRegistry.AgentSpec("t-n", "scribe", AgentRole.WRITE, name = "Nikola K", email = "nikola@team.test", userId = "nikola")
    private val maria = AgentRegistry.AgentSpec("t-m", "helper", AgentRole.WRITE, name = "Maria", email = "maria@team.test", userId = "maria")
    private val plain = AgentRegistry.AgentSpec("t-p", "robot", AgentRole.WRITE, name = "Robot", email = "robot@agents.test")

    private fun obj(r: dev.svod.engine.mcp.ToolResult, k: String): JsonObject? = r.data[k]?.jsonObject

    @Test
    fun `MCP read - bound agent shows person plus agent, later edit by another person moves lastModifiedBy only`() = runBlocking {
        McpFixture(agents = listOf(nikola, maria, plain)).use { fx ->
            val n = fx.registry.byAgentId("scribe")!!
            val m = fx.registry.byAgentId("helper")!!
            fx.tools.write(n, "a.md", "one", null)
            var r = fx.tools.read(n, "a.md")
            assertEquals("Nikola K", obj(r, "author")!!["name"]!!.jsonPrimitive.content)
            assertEquals("scribe", obj(r, "author")!!["agent"]!!.jsonPrimitive.content)
            assertEquals(obj(r, "author"), obj(r, "lastModifiedBy"))

            fx.tools.edit(m, "a.md", "one", "two", false, r.data["revision"]!!.jsonPrimitive.content)
            r = fx.tools.read(n, "a.md")
            assertEquals("nikola@team.test", obj(r, "author")!!["email"]!!.jsonPrimitive.content)
            assertEquals("maria@team.test", obj(r, "lastModifiedBy")!!["email"]!!.jsonPrimitive.content)
            assertEquals("helper", obj(r, "lastModifiedBy")!!["agent"]!!.jsonPrimitive.content)

            val h = fx.tools.history(n, "a.md", 10).data["commits"]!!.jsonArray
            assertEquals(listOf("helper", "scribe"), h.map { it.jsonObject["agent"]!!.jsonPrimitive.content })
        }
    }

    @Test
    fun `MCP read - unbound agent shows itself with no agent field, and a missing path has no attribution`() = runBlocking {
        McpFixture(agents = listOf(plain)).use { fx ->
            val a = fx.registry.byAgentId("robot")!!
            fx.tools.write(a, "a.md", "one", null)
            val r = fx.tools.read(a, "a.md")
            assertEquals("Robot", obj(r, "author")!!["name"]!!.jsonPrimitive.content)
            assertFalse(obj(r, "author")!!.containsKey("agent"))
            val c = fx.tools.history(a, "a.md", 5).data["commits"]!!.jsonArray.single().jsonObject
            assertFalse(c.containsKey("agent"))
        }
    }

    @Test
    fun `MCP read - a path deleted and re-added reports the latest add`() = runBlocking {
        McpFixture(agents = listOf(nikola, maria)).use { fx ->
            val n = fx.registry.byAgentId("scribe")!!
            val m = fx.registry.byAgentId("helper")!!
            fx.tools.write(n, "a.md", "one", null)
            fx.tools.delete(n, "a.md", fx.engine.read("a.md")!!.revision)
            fx.tools.write(m, "a.md", "again", null)
            assertEquals("Maria", obj(fx.tools.read(n, "a.md"), "author")!!["name"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `App API file and history carry attribution and old fields keep their values`() = runBlocking {
        ApiFixture.create().use { fx ->
            fx.engine.write("vault/a.md", "one", null, Author("Nikola K", "nikola@team.test", agent = "scribe"))
            val rev = fx.engine.read("vault/a.md")!!.revision
            fx.engine.write("vault/a.md", "two", rev, Author("Maria", "maria@team.test"))

            val f = Json.parseToJsonElement(fx.get("/api/v1/file?path=${ApiFixture.enc("vault/a.md")}").body()).jsonObject
            // 0.38.0 fields, unchanged
            assertEquals("vault/a.md", f["path"]!!.jsonPrimitive.content)
            assertEquals("two", f["content"]!!.jsonPrimitive.content)
            assertTrue(f["revision"]!!.jsonPrimitive.content.isNotEmpty())
            // new
            assertEquals("Nikola K", f["author"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("scribe", f["author"]!!.jsonObject["agent"]!!.jsonPrimitive.content)
            assertEquals("Maria", f["lastModifiedBy"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertNull(f["lastModifiedBy"]!!.jsonObject["agent"], "unbound author has no agent field")

            val h = Json.parseToJsonElement(fx.get("/api/v1/file/history?path=${ApiFixture.enc("vault/a.md")}").body())
                .jsonObject["commits"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("Maria", "Nikola K"), h.map { it["author"]!!.jsonPrimitive.content })
            assertEquals(listOf("maria@team.test", "nikola@team.test"), h.map { it["email"]!!.jsonPrimitive.content })
            assertTrue(h.all { it.containsKey("commit") && it.containsKey("epochSeconds") && it.containsKey("message") })
            assertNull(h[0]["agent"]); assertEquals("scribe", h[1]["agent"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `0_38_0 shaped agent requests still decode and the API versions are compatible`() {
        val create = Json { ignoreUnknownKeys = true }.decodeFromString<CreateAgentRequest>(
            """{"agentId":"a1","name":"A","role":"WRITE","vaults":["x"],"tokenRef":"env:T"}""",
        )
        assertNull(create.user); assertTrue(create.vaultRoles.isEmpty())
        val update = Json.decodeFromString<UpdateAgentRequest>("""{"name":"B"}""")
        assertNull(update.user); assertNull(update.vaultRoles)
        assertTrue(ApiCompatibility.isCompatible("0.38.0", "0.39.0"))
    }

    @Test
    fun `MCP read latency on a 500-commit vault`() = runBlocking {
        McpFixture(agents = listOf(nikola)).use { fx ->
            val n = fx.registry.byAgentId("scribe")!!
            repeat(500) { i ->
                val path = "n/note-${i % 50}.md"
                fx.tools.write(n, path, "v$i", fx.engine.read(path)?.revision)
            }
            fx.tools.read(n, "n/note-1.md")
            val t0 = System.nanoTime()
            repeat(20) { fx.tools.read(n, "n/note-1.md") }
            val ms = (System.nanoTime() - t0) / 20 / 1e6
            println("READ_LATENCY_MS_500_COMMITS=$ms")
            assertTrue(ms < 2000)
        }
    }
}
