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
}
