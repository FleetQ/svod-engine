package dev.svod.engine.mcp

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixes from the adversarial review of team sharing (ADR-0020 implementation notes, "review"). */
class TeamSharingHardeningTest {

    private val unbound = AgentRegistry.AgentSpec("mal-token", "mallory", AgentRole.WRITE, name = "Mallory", email = "m@x")
    private val bound = AgentRegistry.AgentSpec("nk-token", "nk-claude", AgentRole.WRITE, name = "Nikola", email = "n@x", userId = "nikola")

    @Test
    fun `a path cannot smuggle a Svod-Agent trailer into an unbound commit`() = runBlocking {
        McpFixture(agents = listOf(unbound, bound)).use { fx ->
            val forged = "x\n\nSvod-Agent: nk-claude\ny.md"
            val r = fx.tools.write(fx.registry.byAgentId("mallory")!!, forged, "# hi", null)
            assertTrue(!r.isError, "the engine accepts this path today (review finding 1); the test must exercise it: $r")
            val head = Git.open(fx.root.toFile()).use { it.log().setMaxCount(1).call().first() }
            assertEquals("Mallory", head.authorIdent.name)
            assertTrue(head.getFooterLines("Svod-Agent").isEmpty(), head.fullMessage)
            assertNull(fx.engine.history(forged, 1).first().agent, "history must not attribute the commit to an agent")
            assertNull(fx.engine.attribution(forged).second!!.agent)
        }
    }

    @Test
    fun `carriage returns cannot smuggle a trailer either`() = runBlocking {
        McpFixture(agents = listOf(unbound, bound)).use { fx ->
            for ((i, path) in listOf("c\r\rSvod-Agent: nk-claude\r.md", "d\r\n\r\nSvod-Agent: nk-claude\r\ne.md", "f\n\n \tsvod-agent: nk-claude\ng.md").withIndex()) {
                val r = fx.tools.write(fx.registry.byAgentId("mallory")!!, path, "# hi $i", null)
                if (r.isError) continue // a path the engine refuses forges nothing
                val head = Git.open(fx.root.toFile()).use { it.log().setMaxCount(1).call().first() }
                assertTrue(head.getFooterLines("Svod-Agent").isEmpty(), head.fullMessage)
                assertNull(fx.engine.history(path, 1).firstOrNull()?.agent, "variant $i: ${head.fullMessage}")
                assertNull(fx.engine.attribution(path).second?.agent, "variant $i")
            }
        }
    }

    @Test
    fun `a bound agent keeps exactly one trailer even if its path names another agent`() = runBlocking {
        McpFixture(agents = listOf(unbound, bound)).use { fx ->
            val r = fx.tools.write(fx.registry.byAgentId("nk-claude")!!, "a\nSvod-Agent: mallory\nb.md", "# hi", null)
            assertTrue(!r.isError, r.toString())
            val head = Git.open(fx.root.toFile()).use { it.log().setMaxCount(1).call().first() }
            assertEquals(listOf("nk-claude"), head.getFooterLines("Svod-Agent"), head.fullMessage)
        }
    }

    @Test
    fun `an unbound commit message is unchanged when it carries no trailer text`() = runBlocking {
        McpFixture(agents = listOf(unbound)).use { fx ->
            fx.tools.write(fx.registry.byAgentId("mallory")!!, "plain.md", "# hi", null)
            val head = Git.open(fx.root.toFile()).use { it.log().setMaxCount(1).call().first() }
            assertEquals("write: plain.md", head.fullMessage.trimEnd())
        }
    }

    private suspend fun connect(port: Int, token: String): Client {
        val http = HttpClient(ClientCIO) {
            install(SSE)
            install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
        }
        val client = Client(Implementation(name = "svod-hardening-test", version = "1.0.0"))
        client.connect(StreamableHttpClientTransport(client = http, url = "http://127.0.0.1:$port/mcp"))
        return client
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.text(): String =
        (content.firstOrNull { it is TextContent } as TextContent).text!!

    @Test
    fun `an open session does not keep a role that was taken away`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("hardening-work-")
        val engine = SvodEngine.open(dir, scope)
        val index = IndexService(dir, dir.resolve(".svod/index"), NoneEmbedder).start()
        val tools = SvodTools(engine, index, AuditLog(dir.resolve(".svod/audit/a.log")), RateLimiter.default())
        val writer = AgentRegistry.AgentSpec("w-token", "w", AgentRole.WRITE, vaults = listOf("work"), userId = "ana")
        val registry = AgentRegistry(listOf(writer))
        val server = SvodMcpServer({ vid -> if (vid == "work") tools else null }, "work", registry).start(0)
        try {
            val client = connect(server.port, "w-token")
            assertTrue(client.callTool("write", mapOf("path" to "a.md", "content" to "1")).isError != true)
            val headBefore = engine.history("a.md", 1).first().commit

            registry.reload(listOf(writer.copy(grantRoles = mapOf("work" to GrantRole.READER))))

            // The old session is gone: the call either fails at the transport (404) or is denied.
            val second = runCatching { client.callTool("write", mapOf("path" to "a.md", "content" to "2")) }
            second.getOrNull()?.let { assertEquals(true, it.isError, "a downgraded session must not write: ${it.text()}") }
            assertEquals(headBefore, engine.history("a.md", 1).first().commit, "nothing was written after the downgrade")

            // A fresh session sees the current role.
            val fresh = connect(server.port, "w-token")
            val denied = fresh.callTool("write", mapOf("path" to "a.md", "content" to "3"))
            assertEquals(true, denied.isError)
            assertTrue(denied.text().contains("role reader"), denied.text())
        } finally {
            server.stop(); index.close(); engine.close()
        }
    }
}

class TeamSharingNarrowingTest {
    private val agent = dev.svod.engine.lifecycle.SvodConfig.AgentSettings(
        token = "t", agentId = "a", role = "WRITE", vaults = listOf("work", "team"), vaultRoles = mapOf("work" to "reader"),
    )

    @Test
    fun `dropping the only vault with an explicit role keeps the agent under role enforcement`() {
        val narrowed = agent.narrowedTo(listOf("team"))
        assertEquals(listOf("team"), narrowed.vaults)
        assertEquals(mapOf("team" to "writer"), narrowed.vaultRoles, "still opted in: writer, not legacy WRITE with promote")
    }

    @Test
    fun `narrowing keeps remaining explicit roles and leaves legacy agents legacy`() {
        val two = agent.copy(vaultRoles = mapOf("work" to "reader", "team" to "maintainer"))
        assertEquals(mapOf("team" to "maintainer"), two.narrowedTo(listOf("team")).vaultRoles)
        val legacy = agent.copy(vaultRoles = emptyMap())
        assertEquals(emptyMap(), legacy.narrowedTo(listOf("team")).vaultRoles, "a legacy agent never gains roles")
        val bound = agent.copy(user = "ana")
        assertEquals(emptyMap(), bound.narrowedTo(listOf("team")).vaultRoles, "a bound agent stays opted in through its user")
    }
}
