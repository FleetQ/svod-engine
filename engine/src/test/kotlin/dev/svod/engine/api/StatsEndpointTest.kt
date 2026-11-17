package dev.svod.engine.api

import dev.svod.engine.core.Author
import dev.svod.engine.events.EventBus
import dev.svod.engine.lifecycle.ApiCompatibility
import dev.svod.engine.lifecycle.ConfigStore
import dev.svod.engine.lifecycle.AgentController
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.lifecycle.SvodNode
import dev.svod.engine.mcp.McpFixture
import dev.svod.engine.mcp.SvodMcpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `GET /api/v1/stats`: engine-admin only, counters and timestamps only. */
class StatsEndpointTest {

    private class Fx(localAdmin: Boolean = false) : AutoCloseable {
        val mcp = McpFixture(vaultId = "main")
        private val secrets = Files.createTempDirectory("svod-stats-secrets-")
        private fun keyRef(id: String, value: String) = secrets.resolve("user-$id.key").also { Files.writeString(it, value) }.let { "file:$it" }
        val config = SvodConfig(
            vaults = listOf(SvodConfig.VaultSettings("main", mcp.root.toString())),
            defaultVault = "main",
            agents = listOf(
                SvodConfig.AgentSettings("env:A", "scribe", "WRITE"),
                SvodConfig.AgentSettings("env:B", "reader", "READ_ONLY"),
                SvodConfig.AgentSettings("env:C", "third", "READ_ONLY"),
            ),
            users = listOf(
                SvodConfig.UserSettings("boss", "Boss Person", "boss@co", keyRef("boss", "k-admin"), admin = true),
                SvodConfig.UserSettings("maria", "Maria", "maria@co", keyRef("maria", "k-editor"), grants = listOf(SvodConfig.VaultGrant("main", "EDITOR"))),
            ),
        )
        private val store = ConfigStore(config, null)
        private val view = object : VaultView {
            override val id = "main"
            override val name = "main"
            override val engine = mcp.engine
            override val index = mcp.index
            override val conflicts = null
            override fun syncStatus(): SyncStatusDto? = null
        }
        private val router = object : VaultRouter {
            override fun ids() = listOf("main")
            override fun defaultId() = "main"
            override fun resolve(id: String?): VaultView? = if (id == null || id == "main") view else null
            override fun all() = listOf(view)
        }
        private val running = AppApiServer(
            vaults = router, eventBus = EventBus(),
            config = AppApiServer.Config(localAdmin = localAdmin),
            users = UserRegistry(config.toUserSpecs()),
            agentAdmin = AgentController(store, mcp.registry, "127.0.0.1"),
            lastAgentRequestAt = { mcp.registry.lastAuthenticatedAt },
        ).start(0)
        private val http = HttpClient.newHttpClient()
        fun stats(key: String?): HttpResponse<String> {
            val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.port}/api/v1/stats"))
            if (key != null) b.header("Authorization", "Bearer $key")
            return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString())
        }
        override fun close() { running.stop(); mcp.close() }
    }

    private fun obj(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `admin gets the documented shape with real counts and nothing else`(): Unit = runBlocking {
        Fx().use { fx ->
            fx.mcp.engine.write("a.md", "# Secret title\nsecret body", null, Author("Zed Author", "zed@x.test"))
            fx.mcp.engine.write("notes/b.md", "# second", null, Author("Zed Author", "zed@x.test"))
            fx.mcp.engine.write("image.txt", "not a note", null, Author("Zed Author", "zed@x.test"))
            fx.mcp.engine.delete("image.txt", null, Author("Zed Author", "zed@x.test"))   // lands in .trash/, not a note

            val res = fx.stats("k-admin")
            assertEquals(200, res.statusCode(), res.body())
            val j = obj(res.body())
            assertEquals(setOf("version", "contract", "startedAt", "vaults", "agents", "lastAgentRequestAt").minus("lastAgentRequestAt"), j.keys, "null lastAgentRequestAt is omitted")
            assertEquals(SvodNode.ENGINE_VERSION, j["version"]!!.jsonPrimitive.content)
            assertEquals(ApiCompatibility.CURRENT_CONTRACT_VERSION, j["contract"]!!.jsonPrimitive.content)
            assertTrue(Regex("\\d{4}-\\d\\d-\\d\\dT[^\"]+Z").matches(j["startedAt"]!!.jsonPrimitive.content))
            assertEquals(3, j["agents"]!!.jsonPrimitive.int)
            val v = j["vaults"]!!.jsonArray.single().jsonObject
            assertEquals(setOf("id", "notes", "bytes", "lastCommitAt"), v.keys)
            assertEquals("main", v["id"]!!.jsonPrimitive.content)
            assertEquals(2, v["notes"]!!.jsonPrimitive.int, "a.md and notes/b.md; the trashed file does not count")
            assertTrue(v["bytes"]!!.jsonPrimitive.long >= "# Secret title\nsecret body# second".length, "bytes covers the working tree")
            val head = fx.mcp.engine.history("a.md").first().epochSeconds
            assertTrue(java.time.Instant.parse(v["lastCommitAt"]!!.jsonPrimitive.content).epochSecond >= head)
            for (leak in listOf("Secret", "secret", "a.md", "b.md", "Zed", "zed@", "scribe", "reader", "Boss", "boss@", "Maria"))
                assertFalse(leak in res.body().replace("\"id\":\"main\"", ""), "stats must not carry '$leak': ${res.body()}")
        }
    }

    @Test
    fun `a non-admin key is 403 and no key on a shared engine is 401`(): Unit = runBlocking {
        Fx().use { fx ->
            assertEquals(403, fx.stats("k-editor").statusCode())
            assertEquals(401, fx.stats(null).statusCode())
            assertEquals(401, fx.stats("nope").statusCode())
            assertEquals(200, fx.stats("k-admin").statusCode())
        }
    }

    @Test
    fun `the keyless loopback local UI is allowed`(): Unit = runBlocking {
        Fx(localAdmin = true).use { fx -> assertEquals(200, fx.stats(null).statusCode()) }
    }

    @Test
    fun `lastCommitAt is omitted or an instant, and lastAgentRequestAt is null before and set after an authenticated MCP call`(): Unit = runBlocking {
        Fx().use { fx ->
            val before = obj(fx.stats("k-admin").body())
            assertFalse("lastAgentRequestAt" in before.keys, "none yet")
            val lastCommit = before["vaults"]!!.jsonArray.single().jsonObject["lastCommitAt"]
            assertTrue(
                lastCommit == null || lastCommit is JsonNull || runCatching { java.time.Instant.parse(lastCommit.jsonPrimitive.content) }.isSuccess,
                "lastCommitAt is omitted or an instant, never anything else: $lastCommit",
            )

            val server = SvodMcpServer(fx.mcp.tools, fx.mcp.registry).start(0)
            try {
                val http = HttpClient.newHttpClient()
                fun mcpPost(token: String) = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/mcp"))
                        .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).statusCode()

                assertEquals(401, mcpPost("wrong-token"))
                assertFalse("lastAgentRequestAt" in obj(fx.stats("k-admin").body()).keys, "a rejected token is not an agent request")

                val t0 = java.time.Instant.now().minusSeconds(1)
                mcpPost("write-token")
                val after = obj(fx.stats("k-admin").body())
                val at = java.time.Instant.parse(after["lastAgentRequestAt"]!!.jsonPrimitive.content)
                assertTrue(at.isAfter(t0) && !at.isAfter(java.time.Instant.now().plusSeconds(1)), "set to about now: $at")
            } finally { server.stop() }
        }
    }
}
