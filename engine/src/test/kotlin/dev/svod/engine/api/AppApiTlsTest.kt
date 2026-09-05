package dev.svod.engine.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.network.tls.certificates.buildKeyStore
import kotlinx.coroutines.runBlocking
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0019: a shared engine serves the App API over HTTPS (Netty), not just MCP. */
class AppApiTlsTest {

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun `App API serves over TLS and refuses plain HTTP on that port`() = runBlocking {
        ApiFixture.create().use { fx ->
            val keyStore = buildKeyStore {
                certificate("svod") { password = "changeit"; domains = listOf("127.0.0.1", "localhost") }
            }
            val server = AppApiServer(
                fx.engine, fx.index, fx.eventBus,
                config = AppApiServer.Config(tls = AppApiServer.Tls(keyStore, "svod", "changeit".toCharArray(), "changeit".toCharArray())),
            ).start(0)
            try {
                val https = HttpClient(CIO) { engine { https { trustManager = trustAll } } }
                val r = https.get("https://127.0.0.1:${server.port}/health")
                assertEquals(HttpStatusCode.OK, r.status)
                assertTrue("\"status\"" in r.bodyAsText(), r.bodyAsText())
                val me = https.get("https://127.0.0.1:${server.port}/api/v1/me")
                assertEquals(HttpStatusCode.OK, me.status, "loopback + localAdmin ⇒ the local UI over TLS too")
                https.close()

                val plain = HttpClient(CIO)
                val failed = runCatching { plain.get("http://127.0.0.1:${server.port}/health").status }
                assertTrue(failed.isFailure || failed.getOrNull() != HttpStatusCode.OK, "plain HTTP must not be served on the TLS port")
                plain.close()
            } finally { server.stop() }
        }
    }
}
