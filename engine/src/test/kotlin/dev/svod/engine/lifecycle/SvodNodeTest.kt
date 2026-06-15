package dev.svod.engine.lifecycle

import dev.svod.engine.api.AppApiServer
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.VaultLockedException
import dev.svod.engine.events.EventBus
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvodNodeTest {

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun cfg(vault: Path) = SvodConfig(
        vaultPath = vault.toString(),
        appApiPort = 0,
        mcpPort = 0,
        embedder = SvodConfig.EmbedderSettings(provider = "none"),
        agents = listOf(SvodConfig.AgentSettings("t", "scribe", "WRITE")),
    )

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun put(port: Int, path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(port: Int, path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `embedder control - settings, probe, switch, and raw-key rejection`() {
        val vault = Files.createTempDirectory("svod-emb-")
        val node = SvodNode.start(cfg(vault))
        val port = node.appApiPort
        try {
            // GET /settings exposes the structured embedder block.
            val s = get(port, "/api/v1/settings")
            assertEquals(200, s.statusCode())
            assertTrue(s.body().contains("\"embedder\"") && s.body().contains("\"provider\":\"none\""), s.body())

            // probe a spec without persisting (none → ok, dimension 0)
            val probe = post(port, "/api/v1/embedder/test", """{"provider":"none"}""")
            assertEquals(200, probe.statusCode())
            assertTrue(probe.body().contains("\"ok\":true"), probe.body())

            // switch the embedder (idempotent none → none) returns the new EmbedderInfo
            val put = put(port, "/api/v1/embedder", """{"provider":"none"}""")
            assertEquals(200, put.statusCode())
            assertTrue(put.body().contains("\"provider\":\"none\""), put.body())

            // a RAW api key (not a Secrets ref) is rejected — keys never travel as plaintext
            val bad = put(port, "/api/v1/embedder", """{"provider":"remote-openai","apiKeyRef":"sk-rawsecret"}""")
            assertEquals(422, bad.statusCode())

            // list models: onnx-local enumerates the bundled model with its dimension
            val onnxModels = post(port, "/api/v1/embedder/models", """{"provider":"onnx-local"}""")
            assertEquals(200, onnxModels.statusCode())
            assertTrue(onnxModels.body().contains("\"provider\":\"local-onnx\""), onnxModels.body())
            assertTrue(onnxModels.body().contains("multilingual-e5-small") && onnxModels.body().contains("\"dimension\":384"), onnxModels.body())

            // none → empty list (UI falls back to manual entry)
            val noneModels = post(port, "/api/v1/embedder/models", """{"provider":"none"}""")
            assertEquals(200, noneModels.statusCode())
            assertTrue(noneModels.body().contains("\"models\":[]"), noneModels.body())

            // an unreachable provider endpoint is NOT an error — empty list, 200
            val downModels = post(port, "/api/v1/embedder/models", """{"provider":"local-ollama","endpoint":"http://127.0.0.1:1"}""")
            assertEquals(200, downModels.statusCode())
            assertTrue(downModels.body().contains("\"provider\":\"local-ollama\"") && downModels.body().contains("\"models\":[]"), downModels.body())

            // a raw API key is rejected here too (422), never enumerated
            val badModels = post(port, "/api/v1/embedder/models", """{"provider":"remote-openai","apiKeyRef":"sk-rawsecret"}""")
            assertEquals(422, badModels.statusCode())
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `node starts, serves, shuts down gracefully without losing data`() {
        val vault = Files.createTempDirectory("svod-node-")
        val node = SvodNode.start(cfg(vault))
        val port = node.appApiPort
        try {
            assertEquals(200, get(port, "/health").statusCode())
            val ready = get(port, "/ready")
            assertEquals(200, ready.statusCode())
            assertTrue(ready.body().contains("\"ready\":true"))
            assertTrue(node.isReady())
            assertTrue(node.mcpPort > 0 && node.mcpPort != port, "MCP listens on its own port")

            assertEquals(200, put(port, "/api/v1/file?path=note.md", """{"content":"# N\nlifecycle survives"}""").statusCode())
        } finally {
            node.shutdown()
        }

        // graceful shutdown closed the listener
        var refused = false
        try { get(port, "/health") } catch (_: Exception) { refused = true }
        assertTrue(refused, "App API port must be closed after shutdown")

        // lock released + data durable: a fresh node opens the same vault and reads the write
        val node2 = SvodNode.start(cfg(vault))
        try {
            val r = get(node2.appApiPort, "/api/v1/file?path=note.md")
            assertEquals(200, r.statusCode())
            assertTrue(r.body().contains("lifecycle survives"), "the committed write survived shutdown")
        } finally {
            node2.shutdown()
        }
    }

    @Test
    fun `a second node on the same vault is refused (single-instance)`() {
        val vault = Files.createTempDirectory("svod-node-")
        val node = SvodNode.start(cfg(vault))
        try {
            var refused = false
            try { SvodNode.start(cfg(vault)) } catch (_: VaultLockedException) { refused = true }
            assertTrue(refused, "single-instance must refuse a second node")
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `ready returns 503 until readiness is true`() {
        val vault = Files.createTempDirectory("svod-ready-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SvodEngine.open(vault, scope)
        val index = IndexService(vault, vault.resolve(".svod").resolve("index"), NoneEmbedder).start()
        val api = AppApiServer(engine, index, EventBus(), readiness = { false }).start(0)
        try {
            assertEquals(200, get(api.port, "/health").statusCode(), "liveness is independent of readiness")
            assertEquals(503, get(api.port, "/ready").statusCode())
        } finally {
            api.stop(); index.close(); engine.close()
        }
    }
}
