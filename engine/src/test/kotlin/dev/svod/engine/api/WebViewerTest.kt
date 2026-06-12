package dev.svod.engine.api

import dev.svod.engine.core.SvodEngine
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebViewerTest {

    private val http = HttpClient.newHttpClient()
    private fun get(port: Int, path: String) =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `viewer is served same-origin when configured, and API routes still win`() {
        val vault = Files.createTempDirectory("svod-viewer-")
        val viewer = Files.createTempDirectory("svod-web-")
        Files.writeString(viewer.resolve("index.html"), "<!doctype html><title>Svod</title><body data-svod-viewer>ok")
        Files.writeString(viewer.resolve("app.js"), "console.log('svod');")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SvodEngine.open(vault, scope)
        val index = IndexService(vault, vault.resolve(".svod").resolve("index"), NoneEmbedder).start()
        val api = AppApiServer(
            engine, index, EventBus(),
            config = AppApiServer.Config(webViewerPath = viewer.toString()),
        ).start(0)
        try {
            val root = get(api.port, "/")
            assertEquals(200, root.statusCode())
            assertTrue(root.body().contains("data-svod-viewer"), "index.html served at /")
            assertTrue(get(api.port, "/app.js").body().contains("svod"), "static assets served")
            // explicit API/lifecycle routes are not shadowed by the static handler
            assertEquals(200, get(api.port, "/health").statusCode())
            assertTrue(get(api.port, "/api/v1/settings").body().contains("apiVersion"))
        } finally {
            api.stop(); index.close(); engine.close()
        }
    }

    @Test
    fun `no viewer route when unconfigured`() {
        val vault = Files.createTempDirectory("svod-viewer-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SvodEngine.open(vault, scope)
        val index = IndexService(vault, vault.resolve(".svod").resolve("index"), NoneEmbedder).start()
        val api = AppApiServer(engine, index, EventBus()).start(0)
        try {
            assertEquals(404, get(api.port, "/").statusCode())
            assertEquals(200, get(api.port, "/health").statusCode())
        } finally {
            api.stop(); index.close(); engine.close()
        }
    }
}
