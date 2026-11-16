package dev.svod.engine.api

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertTrue

/** GET /agents reports the configured public MCP URL, and falls back to the bind address without one. */
class PublicMcpUrlTest {

    private val admin = object : AgentAdmin {
        override fun list() = AgentsView(emptyList(), 7518)
        override suspend fun create(req: CreateAgentRequest) = throw UnsupportedOperationException()
        override suspend fun update(id: String, req: UpdateAgentRequest) = throw UnsupportedOperationException()
        override suspend fun delete(id: String) = throw UnsupportedOperationException()
    }

    private fun agentsBody(publicMcpUrl: String?): String = ApiFixture().use { fx ->
        val server = AppApiServer(
            vaults = SingleVaultRouter(fx.engine, fx.index, null, { null }),
            eventBus = fx.eventBus,
            config = AppApiServer.Config(publicMcpUrl = publicMcpUrl),
            agentAdmin = admin,
        ).start(0)
        try {
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/api/v1/agents")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
        } finally { server.stop() }
    }

    @Test
    fun `mcpUrl is the configured public URL`() {
        val body = agentsBody("https://svod.fleetq.net/t/acme/mcp")
        assertTrue("\"mcpUrl\":\"https://svod.fleetq.net/t/acme/mcp\"" in body.replace(" ", ""), body)
    }

    @Test
    fun `mcpUrl falls back to the bind address when no public URL is configured`() {
        val body = agentsBody(null)
        assertTrue("\"mcpUrl\":\"http://127.0.0.1:7518\"" in body.replace(" ", ""), body)
    }
}
