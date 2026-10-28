package dev.svod.engine.api

import dev.svod.engine.core.Author
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Prometheus scrape endpoint. It lives at the root (`/metrics`), outside the versioned
 * /api/v1 contract, and returns text/plain exposition — so it is verified here rather than in
 * the JSON contract test.
 */
class AppMetricsTest {

    @Test
    fun `GET metrics returns prometheus exposition with live values`() = runBlocking {
        ApiFixture.create().use { fx ->
            fx.engine.write("a.md", "# A\nbody", null, Author("ui", "ui@svod.local"))
            fx.index.waitIdle()

            val resp = fx.get("/metrics")
            assertEquals(200, resp.statusCode())
            assertTrue(resp.headers().firstValue("content-type").orElse("").startsWith("text/plain"), "prometheus is text/plain")

            val body = resp.body()
            // exposition framing
            assertTrue(body.contains("# TYPE svod_up gauge"), body)
            assertTrue(body.contains("\nsvod_up 1"), body)
            // per-vault series carry a vault label and reflect the write we just made
            assertTrue(Regex("""svod_write_total\{vault="[^"]+"} [1-9]""").containsMatchIn(body), body)
            assertTrue(body.contains("svod_index_doc_count{vault="), body)
            // embedding-state is a one-hot gauge across the four states
            assertTrue(body.contains("svod_embedding_state{vault=") && body.contains("state=\"idle\""), body)
        }
    }

    @Test
    fun `metrics report the sync hold age per synced vault and the live MCP sessions`() = runBlocking {
        ApiFixture.create().use { fx ->
            val sync = dev.svod.engine.sync.SyncEngine(
                fx.engine, dev.svod.engine.sync.SyncGit(fx.root), fx.conflicts, fx.eventBus, "default", "machineA",
            )
            val api = AppApiServer(
                SingleVaultRouter(fx.engine, fx.index, fx.conflicts, { null }), fx.eventBus,
                syncEngineOf = { sync }, mcpLiveSessions = { 7 },
            ).start(0)
            try {
                val body = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:${api.port}/metrics")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(),
                ).body()
                assertTrue(body.contains("# TYPE svod_sync_held_seconds gauge"), body)
                assertTrue(body.contains("svod_sync_held_seconds{vault=\"default\"} 0"), body)
                assertTrue(body.contains("svod_sync_out_of_sync_seconds{vault=\"default\"} 0"), body)
                assertTrue(body.contains("\nsvod_mcp_live_sessions 7"), body)
            } finally { api.stop() }

            // Without a sync engine or an MCP server the series are left out, not reported as 0.
            val plain = fx.get("/metrics").body()
            assertTrue("svod_sync_held_seconds" !in plain && "svod_mcp_live_sessions" !in plain, plain)
        }
    }
}
