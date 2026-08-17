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

    /**
     * The SPA fallback must not swallow the API namespace.
     *
     * Measured on the live engine before this guard: `/api/v1/does-not-exist`, `/api/v1/graph/nope`
     * and `/api/v9/settings` all returned **200 text/html**, so a client could not tell "no such
     * endpoint" from "endpoint returned a page" — and a typed client raised a DECODING error rather
     * than a not-found one. This is the test that would have caught it.
     */
    @Test
    fun `an unknown API path is a JSON 404, not the viewer's index page`() {
        val vault = Files.createTempDirectory("svod-viewer-")
        val viewer = Files.createTempDirectory("svod-web-")
        Files.writeString(viewer.resolve("index.html"), "<!doctype html><title>Svod</title><body data-svod-viewer>ok")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SvodEngine.open(vault, scope)
        val index = IndexService(vault, vault.resolve(".svod").resolve("index"), NoneEmbedder).start()
        val api = AppApiServer(
            engine, index, EventBus(),
            config = AppApiServer.Config(webViewerPath = viewer.toString()),
        ).start(0)
        try {
            for (path in listOf("/api/v1/does-not-exist", "/api/v1/graph/nope", "/api/v9/settings")) {
                val r = get(api.port, path)
                assertEquals(404, r.statusCode(), "$path must be a 404")
                assertTrue(
                    r.body().contains("unknown_route"),
                    "$path must answer with the JSON error shape, got: ${r.body().take(120)}",
                )
                assertTrue("data-svod-viewer" !in r.body(), "$path must not be answered with the SPA page")
            }
            // The other direction: the guard must not shadow real routes or the viewer's own paths.
            assertEquals(200, get(api.port, "/api/v1/settings").statusCode())
            assertEquals(200, get(api.port, "/").statusCode())
            assertTrue(get(api.port, "/some/client/side/route").body().contains("data-svod-viewer"))
            assertEquals(200, get(api.port, "/health").statusCode())
        } finally {
            api.stop(); index.close(); engine.close()
        }
    }

    @Test
    fun `unknown API paths are a JSON 404 even with no viewer configured`() {
        val vault = Files.createTempDirectory("svod-viewer-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SvodEngine.open(vault, scope)
        val index = IndexService(vault, vault.resolve(".svod").resolve("index"), NoneEmbedder).start()
        val api = AppApiServer(engine, index, EventBus()).start(0)
        try {
            val r = get(api.port, "/api/v1/does-not-exist")
            assertEquals(404, r.statusCode())
            // Without the guard this is Ktor's bare 404 with an empty body — the answer must not
            // depend on whether the viewer happens to be configured.
            assertTrue(r.body().contains("unknown_route"), "got: ${r.body().take(120)}")
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
