package dev.svod.engine.mcp

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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Proves the MCP tools work over the real streamable-HTTP wire with bearer auth + identity. */
class McpHttpIntegrationTest {

    private suspend fun connect(port: Int, token: String): Client {
        val http = HttpClient(ClientCIO) {
            install(SSE)
            install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
        }
        val client = Client(Implementation(name = "svod-test-client", version = "1.0.0"))
        client.connect(StreamableHttpClientTransport(client = http, url = "http://127.0.0.1:$port/mcp"))
        return client
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.text(): String =
        (content.firstOrNull { it is TextContent } as TextContent).text!!

    @Test
    fun `tools work over HTTP with bearer auth and identity flows to git`() = runBlocking {
        McpFixture().use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                val port = server.port

                // --- write agent ---
                val scribe = connect(port, "write-token")
                try {
                    assertEquals(12, scribe.listTools().tools.size, "all 12 tools advertised")

                    val w = scribe.callTool("write", mapOf("path" to "http/a.md", "content" to "# A\nwritten via mcp"))
                    val wJson = Json.parseToJsonElement(w.text()).jsonObject
                    assertEquals("ok", wJson["status"]!!.jsonPrimitive.content)

                    // the commit is authored by the authenticated agent
                    assertEquals("Scribe", fx.engine.history("http/a.md").first().authorName)

                    fx.index.waitIdle() // let the off-path indexer catch up before asserting search
                    val s = scribe.callTool("search", mapOf("query" to "mcp", "mode" to "keyword"))
                    assertTrue(s.text().contains("http/a.md"), "search over HTTP returns the note")
                } finally { scribe.close() }

                // --- read-only agent is denied over the wire ---
                val reader = connect(port, "read-token")
                try {
                    val denied = reader.callTool("write", mapOf("path" to "http/x.md", "content" to "no"))
                    assertEquals(true, denied.isError, "read-only agent's write must be an error result")
                    assertTrue(denied.text().contains("denied"))
                    // and it can still read
                    val r = reader.callTool("read", mapOf("path" to "http/a.md"))
                    assertTrue(r.text().contains("written via mcp"))
                } finally { reader.close() }

                // audit trail captured the HTTP-driven actions
                assertTrue(fx.audit.entries().any { it.agentId == "scribe" && it.tool == "write" && it.outcome == "ok" })
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `explicit null expectedRevision creates a new file, not a conflict`() = runBlocking {
        McpFixture().use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                val c = connect(server.port, "write-token")
                try {
                    val r = c.callTool("write", mapOf("path" to "n/fresh.md", "content" to "# Fresh", "expectedRevision" to null))
                    assertTrue(r.text().contains("\"status\":\"ok\""), "JSON null must mean create, got: ${r.text()}")
                } finally { c.close() }
            } finally { server.stop() }
        }
    }

    @Test
    fun `an unknown bearer token is rejected`() = runBlocking {
        McpFixture().use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                var rejected = false
                try {
                    val c = connect(server.port, "bogus-token")
                    c.listTools()
                    c.close()
                } catch (_: Throwable) {
                    rejected = true
                }
                assertTrue(rejected, "connecting with an unknown token must fail")
            } finally {
                server.stop()
            }
        }
    }
}
