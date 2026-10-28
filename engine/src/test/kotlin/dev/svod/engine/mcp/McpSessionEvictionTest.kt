package dev.svod.engine.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * #37: session-based (2025-11-25) MCP sessions whose client went away without DELETE stayed in
 * memory for the life of the process (3344 live sessions after 12 days). Idle sessions are now
 * evicted after `mcp.sessionIdleMinutes`, and an evicted id gets the 404 that makes a client
 * re-initialize.
 */
class McpSessionEvictionTest {

    private fun http(token: String) = HttpClient(ClientCIO) {
        install(SSE)
        install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
    }

    /** One raw JSON-RPC POST, optionally on a session. Raw so no client opens a background SSE stream. */
    private suspend fun HttpClient.rpc(port: Int, body: String, sessionId: String? = null) =
        post("http://127.0.0.1:$port/mcp") {
            sessionId?.let { header("mcp-session-id", it) }
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** A 2025-11-25 handshake by hand; returns the session id the server handed out. */
    private suspend fun HttpClient.openSession(port: Int, n: Int): String {
        val init = rpc(port, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
            """"capabilities":{},"clientInfo":{"name":"evict-test-$n","version":"1.0.0"}}}""")
        assertEquals(HttpStatusCode.OK, init.status)
        val id = assertNotNull(init.headers["mcp-session-id"], "the handshake hands out a session id")
        rpc(port, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", id)
        return id
    }

    @Test
    fun `sessions idle past the TTL are evicted and an evicted id gets 404`() = runBlocking {
        McpFixture().use { fx ->
            val now = AtomicLong(1_000_000L)
            val mcp = SvodMcpServer({ fx.tools }, "default", fx.registry, sessionIdleTtl = Duration.ofMinutes(30), clock = { now.get() })
            val server = mcp.start(0)
            val h = http("write-token")
            try {
                // Open N sessions and walk away from them without DELETE (the Ctrl+C case).
                val ids = (1..5).map { h.openSession(server.port, it) }
                assertEquals(5, mcp.liveSessions())

                now.addAndGet(Duration.ofMinutes(29).toMillis())
                assertEquals(0, mcp.evictIdle(), "not idle long enough yet")
                assertEquals(5, mcp.liveSessions())

                // One session stays active: a request just before the sweep keeps it alive.
                val active = h.rpc(server.port, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", ids[0])
                assertEquals(HttpStatusCode.OK, active.status)

                now.addAndGet(Duration.ofMinutes(2).toMillis()) // 31 min since the others were last seen
                assertEquals(4, mcp.evictIdle())
                assertEquals(1, mcp.liveSessions(), "only the session that kept talking is left")

                val gone = h.rpc(server.port, """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", ids[1])
                assertEquals(HttpStatusCode.NotFound, gone.status, "an evicted session id must get 404 so the client re-initializes")

                now.addAndGet(Duration.ofMinutes(31).toMillis())
                assertEquals(1, mcp.evictIdle())
                assertEquals(0, mcp.liveSessions())

                // A client that comes back simply opens a new session.
                val again = http("write-token")
                try {
                    val t = StreamableHttpClientTransport(client = again, url = "http://127.0.0.1:${server.port}/mcp")
                    val c = Client(Implementation(name = "evict-test-again", version = "1.0.0"))
                    c.connect(t)
                    assertEquals(20, c.listTools().tools.size)
                    assertEquals(1, mcp.liveSessions())
                } finally { again.close() }
            } finally {
                h.close()
                server.stop()
            }
        }
    }

    @Test
    fun `config defaults to a 30 minute idle TTL, reads mcp sessionIdleMinutes and rejects less than 1`() {
        val dir = java.nio.file.Files.createTempDirectory("svod-config-")
        val plain = dir.resolve("plain.json").also { java.nio.file.Files.writeString(it, """{"vaultPath":"/tmp/v"}""") }
        val set = dir.resolve("set.json").also { java.nio.file.Files.writeString(it, """{"vaultPath":"/tmp/v","mcp":{"sessionIdleMinutes":45}}""") }
        assertEquals(30, dev.svod.engine.lifecycle.SvodConfig.load(plain).mcp.sessionIdleMinutes, "an existing config needs no edit")
        assertEquals(45, dev.svod.engine.lifecycle.SvodConfig.load(set).mcp.sessionIdleMinutes)
        val errors = dev.svod.engine.lifecycle.SvodConfig(vaultPath = "/tmp/v", mcp = dev.svod.engine.lifecycle.SvodConfig.McpSettings(0)).validate()
        assertEquals(listOf("mcp.sessionIdleMinutes must be >= 1, was 0"), errors.filter { "mcp." in it })
    }
}
