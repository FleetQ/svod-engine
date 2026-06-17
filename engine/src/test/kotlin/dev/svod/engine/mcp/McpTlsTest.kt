package dev.svod.engine.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.network.tls.certificates.buildKeyStore
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpTlsTest {

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun `MCP serves over TLS with bearer auth`() = runBlocking {
        McpFixture().use { fx ->
            val keyStore = buildKeyStore {
                certificate("svod") { password = "changeit"; domains = listOf("127.0.0.1", "localhost") }
            }
            val server = SvodMcpServer(fx.tools, fx.registry).start(
                0,
                SvodMcpServer.Tls(keyStore, "svod", "changeit".toCharArray(), "changeit".toCharArray()),
            )
            try {
                val http = HttpClient(ClientCIO) {
                    install(SSE)
                    install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer write-token") }
                    engine { https { trustManager = trustAll } }
                }
                val client = Client(Implementation("tls-test-client", "1.0.0"))
                client.connect(StreamableHttpClientTransport(client = http, url = "https://127.0.0.1:${server.port}/mcp"))
                try {
                    assertEquals(14, client.listTools().tools.size, "tools listed over HTTPS")
                    val r = client.callTool("write", mapOf("path" to "tls/note.md", "content" to "# Over TLS"))
                    val text = (r.content.firstOrNull { it is TextContent } as TextContent).text!!
                    assertTrue(text.contains("\"status\":\"ok\""), "write over TLS: $text")
                    assertEquals("Scribe", fx.engine.history("tls/note.md").first().authorName)
                } finally { client.close() }
            } finally { server.stop() }
        }
    }
}
