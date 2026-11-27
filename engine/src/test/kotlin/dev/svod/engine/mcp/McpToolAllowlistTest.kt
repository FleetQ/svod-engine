package dev.svod.engine.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** An agent with a `tools` allowlist is offered (and can call) only those tools, on both wire formats. */
class McpToolAllowlistTest {

    private val narrow = AgentRegistry.AgentSpec(
        "narrow-token", "narrow", AgentRole.WRITE, name = "Narrow", email = "narrow@agents.test",
        tools = listOf("search", "context_pack", "remember"),
    )

    private suspend fun HttpClient.statelessToolNames(port: Int, token: String): List<String> {
        val meta = """"_meta":{"${McpProtocol.META_PROTOCOL_VERSION}":"${McpProtocol.V2026_07_28}",""" +
            """"${McpProtocol.META_CLIENT_INFO}":{"name":"allowlist-test","version":"1.0.0"},""" +
            """"${McpProtocol.META_CLIENT_CAPABILITIES}":{}}"""
        val res = post("http://127.0.0.1:$port/mcp") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{$meta}}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }

    private suspend fun connect(port: Int, token: String): Client {
        val http = HttpClient(ClientCIO) {
            install(SSE)
            install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
        }
        val client = Client(Implementation(name = "allowlist-test", version = "1.0.0"))
        client.connect(StreamableHttpClientTransport(client = http, url = "http://127.0.0.1:$port/mcp"))
        return client
    }

    @Test
    fun `an allowlisted agent sees and reaches only its listed tools`(): Unit = runBlocking {
        McpFixture(agents = listOf(WRITE_AGENT, READ_AGENT, narrow)).use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                val expected = setOf("search", "context_pack", "remember")
                HttpClient(ClientCIO).use { http ->
                    val stateless = http.statelessToolNames(server.port, narrow.token)
                    assertEquals(3, stateless.size, "stateless: $stateless")
                    assertEquals(expected, stateless.toSet())
                    assertEquals(21, http.statelessToolNames(server.port, WRITE_AGENT.token).size, "no allowlist: unchanged")
                }
                val c = connect(server.port, narrow.token)
                try {
                    val session = c.listTools().tools.map { it.name }
                    assertEquals(3, session.size, "session: $session")
                    assertEquals(expected, session.toSet())
                    assertEquals(c.outcome("no_such_tool"), c.outcome("write"), "unlisted write fails like an unknown tool")
                } finally { c.close() }
            } finally { server.stop() }
        }
    }
}
