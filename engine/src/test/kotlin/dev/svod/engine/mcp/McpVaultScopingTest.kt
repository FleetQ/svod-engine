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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-agent vault scoping: an agent granted only "work" gets a tool set bound to the work vault,
 * so its writes land in work and never touch personal — isolation by construction.
 */
class McpVaultScopingTest {

    private suspend fun connect(port: Int, token: String): Client {
        val http = HttpClient(ClientCIO) {
            install(SSE)
            install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
        }
        val client = Client(Implementation(name = "svod-scoping-test", version = "1.0.0"))
        client.connect(StreamableHttpClientTransport(client = http, url = "http://127.0.0.1:$port/mcp"))
        return client
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.text(): String =
        (content.firstOrNull { it is TextContent } as TextContent).text!!

    @Test
    fun `a work-scoped agent writes only to the work vault`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workDir = Files.createTempDirectory("scope-work-")
        val personalDir = Files.createTempDirectory("scope-personal-")
        val workEngine = SvodEngine.open(workDir, scope)
        val personalEngine = SvodEngine.open(personalDir, scope)
        val workIndex = IndexService(workDir, workDir.resolve(".svod/index"), NoneEmbedder).start()
        val personalIndex = IndexService(personalDir, personalDir.resolve(".svod/index"), NoneEmbedder).start()
        val workTools = SvodTools(workEngine, workIndex, AuditLog(workDir.resolve(".svod/audit/a.log")), RateLimiter.default())
        val personalTools = SvodTools(personalEngine, personalIndex, AuditLog(personalDir.resolve(".svod/audit/a.log")), RateLimiter.default())
        val toolsByVault = mapOf("work" to workTools, "personal" to personalTools)

        val registry = AgentRegistry(listOf(
            AgentRegistry.AgentSpec("work-token", "friday", AgentRole.WRITE, name = "Friday", vaults = listOf("work")),
            AgentRegistry.AgentSpec("personal-token", "sage", AgentRole.WRITE, name = "Sage", vaults = listOf("personal")),
            AgentRegistry.AgentSpec("both-token", "polly", AgentRole.WRITE, name = "Polly", vaults = listOf("work", "personal")),
        ))
        val server = SvodMcpServer({ vid -> toolsByVault[vid] }, "work", registry).start(0)
        try {
            // the work agent writes a note (no vault arg ⇒ its primary vault, work)
            val friday = connect(server.port, "work-token")
            try {
                val w = friday.callTool("write", mapOf("path" to "shared.md", "content" to "from work"))
                assertTrue(w.text().contains("\"status\":\"ok\""), w.text())
                // ...but targeting a vault it is NOT granted is denied
                val denied = friday.callTool("write", mapOf("path" to "x.md", "content" to "no", "vault" to "personal"))
                assertEquals(true, denied.isError, "ungranted vault must be denied")
                assertTrue(denied.text().contains("denied") && denied.text().contains("not granted"), denied.text())
            } finally { friday.close() }

            // it landed in the WORK vault, not personal
            assertTrue(Files.exists(workDir.resolve("shared.md")), "work agent's write must be in the work vault")
            assertTrue(!Files.exists(personalDir.resolve("shared.md")), "work agent must not touch the personal vault")

            // the personal agent's same-named write goes to its own vault, independently
            val sage = connect(server.port, "personal-token")
            try {
                sage.callTool("write", mapOf("path" to "shared.md", "content" to "from personal"))
            } finally { sage.close() }
            assertTrue(Files.readString(personalDir.resolve("shared.md")).contains("from personal"))
            assertTrue(Files.readString(workDir.resolve("shared.md")).contains("from work"), "the two vaults stay independent")

            // a MULTI-grant agent reaches BOTH vaults via the vault arg (per-request selection)
            val polly = connect(server.port, "both-token")
            try {
                assertTrue(polly.callTool("write", mapOf("path" to "p.md", "content" to "polly work", "vault" to "work")).text().contains("\"status\":\"ok\""))
                assertTrue(polly.callTool("write", mapOf("path" to "p.md", "content" to "polly personal", "vault" to "personal")).text().contains("\"status\":\"ok\""))
            } finally { polly.close() }
            assertTrue(Files.readString(workDir.resolve("p.md")).contains("polly work"))
            assertTrue(Files.readString(personalDir.resolve("p.md")).contains("polly personal"), "multi-grant agent reached both granted vaults")
        } finally {
            server.stop()
            workIndex.close(); personalIndex.close(); workEngine.close(); personalEngine.close()
        }
    }
}
