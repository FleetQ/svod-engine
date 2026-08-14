package dev.svod.engine.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 2026-07-28 wire format (SEP-2575 no handshake, SEP-2567 no sessions, SEP-2243 routing
 * headers, SEP-2549 list cache hints) served on the same `/mcp` endpoint as the 2025-11-25
 * handshake+session format, with the format chosen per request.
 */
class McpStatelessProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun client() = HttpClient(ClientCIO)

    /** One stateless POST — no session, no prior handshake, `_meta` on the request itself. */
    private suspend fun HttpClient.rpc(
        port: Int,
        body: String,
        token: String = "write-token",
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse = post("http://127.0.0.1:$port/mcp") {
        header(HttpHeaders.Authorization, "Bearer $token")
        headers.forEach { (k, v) -> header(k, v) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun meta(version: String = McpProtocol.V2026_07_28): String =
        """"_meta":{"${McpProtocol.META_PROTOCOL_VERSION}":"$version",""" +
            """"${McpProtocol.META_CLIENT_INFO}":{"name":"stateless-test","version":"1.0.0"},""" +
            """"${McpProtocol.META_CLIENT_CAPABILITIES}":{}}"""

    private fun HttpResponse.jsonBody(): JsonObject = runBlocking { Json.parseToJsonElement(bodyAsText()).jsonObject }

    @Test
    fun `server discover replaces the handshake`() = runBlocking {
        McpFixture().use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                client().use { http ->
                    val res = http.rpc(server.port, """{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{${meta()}}}""")
                    assertEquals(HttpStatusCode.OK, res.status)
                    val result = res.jsonBody()["result"]!!.jsonObject
                    assertEquals(McpProtocol.V2026_07_28, result["protocolVersion"]!!.jsonPrimitive.content)
                    assertEquals("svod", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                    assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"), "advertises the tools capability")
                }
            } finally { server.stop() }
        }
    }

    @Test
    fun `tools list is served without a session and carries SEP-2549 cache hints`() = runBlocking {
        McpFixture().use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                client().use { http ->
                    val res = http.rpc(
                        server.port,
                        """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{${meta()}}}""",
                        headers = mapOf(
                            McpProtocol.HEADER_PROTOCOL_VERSION to McpProtocol.V2026_07_28,
                            McpProtocol.HEADER_METHOD to "tools/list",
                        ),
                    )
                    assertEquals(HttpStatusCode.OK, res.status)
                    val result = res.jsonBody()["result"]!!.jsonObject
                    assertEquals(15, result["tools"]!!.jsonArray.size, "all 15 tools advertised statelessly")
                    assertEquals(McpProtocol.TOOLS_TTL_MS, result["ttlMs"]!!.jsonPrimitive.long)
                    assertEquals(McpProtocol.TOOLS_CACHE_SCOPE, result["cacheScope"]!!.jsonPrimitive.content)
                    // the schema still travels with each tool
                    val read = result["tools"]!!.jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == "read" }
                    assertTrue(read.jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject.containsKey("path"))
                }
            } finally { server.stop() }
        }
    }

    @Test
    fun `tools call runs with identity and no handshake`() = runBlocking {
        McpFixture().use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                client().use { http ->
                    val write = http.rpc(
                        server.port,
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"write",""" +
                            """"arguments":{"path":"stateless/a.md","content":"# A\nno handshake"},${meta()}}}""",
                        headers = mapOf(
                            McpProtocol.HEADER_PROTOCOL_VERSION to McpProtocol.V2026_07_28,
                            McpProtocol.HEADER_METHOD to "tools/call",
                            McpProtocol.HEADER_NAME to "write",
                        ),
                    )
                    assertEquals(HttpStatusCode.OK, write.status)
                    val text = write.jsonBody()["result"]!!.jsonObject["content"]!!.jsonArray
                        .first().jsonObject["text"]!!.jsonPrimitive.content
                    assertTrue(text.contains("\"status\":\"ok\""), "stateless write must succeed, got: $text")

                    // the bearer identity still reaches git — statelessness does not lose the author
                    assertEquals("Scribe", fx.engine.history("stateless/a.md").first().authorName)

                    // and a read-only agent is still denied over the stateless path
                    val denied = http.rpc(
                        server.port,
                        """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"write",""" +
                            """"arguments":{"path":"stateless/x.md","content":"no"},${meta()}}}""",
                        token = "read-token",
                    )
                    val result = denied.jsonBody()["result"]!!.jsonObject
                    assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
                }
            } finally { server.stop() }
        }
    }

    @Test
    fun `a request whose headers disagree with its body is rejected`() = runBlocking {
        McpFixture().use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                client().use { http ->
                    // Mcp-Method lies about the body's method
                    val badMethod = http.rpc(
                        server.port,
                        """{"jsonrpc":"2.0","id":5,"method":"tools/list","params":{${meta()}}}""",
                        headers = mapOf(McpProtocol.HEADER_METHOD to "tools/call"),
                    )
                    assertEquals(HttpStatusCode.BadRequest, badMethod.status)
                    assertEquals(-32600, badMethod.jsonBody()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

                    // Mcp-Name names a different tool than params.name
                    val badName = http.rpc(
                        server.port,
                        """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"read",""" +
                            """"arguments":{"path":"stateless/a.md"},${meta()}}}""",
                        headers = mapOf(McpProtocol.HEADER_NAME to "write"),
                    )
                    assertEquals(HttpStatusCode.BadRequest, badName.status)

                    // MCP-Protocol-Version header contradicts params._meta
                    val badVersion = http.rpc(
                        server.port,
                        """{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{${meta()}}}""",
                        headers = mapOf(McpProtocol.HEADER_PROTOCOL_VERSION to McpProtocol.V2025_11_25),
                    )
                    assertEquals(HttpStatusCode.BadRequest, badVersion.status)

                    // …and the honest version of the same call still passes
                    val ok = http.rpc(
                        server.port,
                        """{"jsonrpc":"2.0","id":8,"method":"tools/list","params":{${meta()}}}""",
                        headers = mapOf(
                            McpProtocol.HEADER_PROTOCOL_VERSION to McpProtocol.V2026_07_28,
                            McpProtocol.HEADER_METHOD to "tools/list",
                        ),
                    )
                    assertEquals(HttpStatusCode.OK, ok.status)
                }
            } finally { server.stop() }
        }
    }

    @Test
    fun `notifications are accepted and unknown methods report method not found`() = runBlocking {
        McpFixture().use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                client().use { http ->
                    val notification = http.rpc(server.port, """{"jsonrpc":"2.0","method":"notifications/progress","params":{${meta()}}}""")
                    assertEquals(HttpStatusCode.Accepted, notification.status)
                    assertEquals("", notification.bodyAsText())

                    val unknown = http.rpc(server.port, """{"jsonrpc":"2.0","id":9,"method":"does/not/exist","params":{${meta()}}}""")
                    val error = unknown.jsonBody()["error"]!!.jsonObject
                    assertEquals(-32601, error["code"]!!.jsonPrimitive.int)
                    assertNull(unknown.jsonBody()["result"])
                }
            } finally { server.stop() }
        }
    }

    @Test
    fun `both wire formats work against one running server`() = runBlocking {
        McpFixture().use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                // --- 2025-11-25: initialize + Mcp-Session-Id, driven by the SDK client ---
                val legacyHttp = HttpClient(ClientCIO) {
                    install(SSE)
                    install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer write-token") }
                }
                val legacy = Client(Implementation(name = "legacy-test-client", version = "1.0.0"))
                legacy.connect(StreamableHttpClientTransport(client = legacyHttp, url = "http://127.0.0.1:${server.port}/mcp"))
                try {
                    assertEquals(15, legacy.listTools().tools.size, "the handshake path still advertises 15 tools")
                    legacy.callTool("write", mapOf("path" to "both/legacy.md", "content" to "# legacy"))
                    assertEquals("Scribe", fx.engine.history("both/legacy.md").first().authorName)
                } finally { legacy.close(); legacyHttp.close() }

                // --- 2026-07-28: same endpoint, same instant, no session ---
                client().use { http ->
                    val res = http.rpc(
                        server.port,
                        """{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"read",""" +
                            """"arguments":{"path":"both/legacy.md"},${meta()}}}""",
                    )
                    assertEquals(HttpStatusCode.OK, res.status)
                    val text = res.jsonBody()["result"]!!.jsonObject["content"]!!.jsonArray
                        .first().jsonObject["text"]!!.jsonPrimitive.content
                    assertTrue(text.contains("# legacy"), "stateless read must see the note the legacy session wrote")
                }
            } finally { server.stop() }
        }
    }

    @Test
    fun `an unknown bearer token is rejected on the stateless path too`() = runBlocking {
        McpFixture().use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                client().use { http ->
                    val res = http.rpc(server.port, """{"jsonrpc":"2.0","id":11,"method":"tools/list","params":{${meta()}}}""", token = "bogus")
                    assertEquals(HttpStatusCode.Unauthorized, res.status)
                }
            } finally { server.stop() }
        }
    }
}
