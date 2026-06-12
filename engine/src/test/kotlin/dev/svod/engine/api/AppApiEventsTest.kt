package dev.svod.engine.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

class AppApiEventsTest {

    @Test
    fun `websocket streams live agent activity, commit and index events`() = runBlocking {
        ApiFixture.create().use { fx ->
            val received = CopyOnWriteArrayList<String>()
            val client = HttpClient(CIO) { install(WebSockets) }
            val job = launch(Dispatchers.IO) {
                try {
                    client.webSocket(host = "127.0.0.1", port = fx.port, path = "/api/v1/events") {
                        for (frame in incoming) if (frame is Frame.Text) received.add(frame.readText())
                    }
                } catch (_: Exception) {
                }
            }
            delay(700) // let the WebSocket establish before we emit

            // an agent write drives agent.activity + commit.created; the indexer drives index.updated
            fx.tools.write(fx.writeAgent, "ev/a.md", "# A\nlive feed content", expectedRevision = null)
            fx.index.waitIdle()

            withTimeout(5000) {
                while (received.none { it.contains("\"agent.activity\"") } ||
                    received.none { it.contains("\"index.updated\"") }
                ) delay(50)
            }

            assertTrue(received.any { it.contains("\"type\":\"agent.activity\"") }, "agent.activity: $received")
            assertTrue(received.any { it.contains("\"type\":\"commit.created\"") }, "commit.created: $received")
            assertTrue(received.any { it.contains("\"type\":\"index.updated\"") }, "index.updated: $received")

            job.cancel()
            client.close()
        }
    }
}
