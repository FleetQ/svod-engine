package dev.svod.engine.api

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.sync.ConflictStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The reference viewer's Sync conflicts pane, checked against the real bundled files and endpoints. */
class WebViewerConflictsTest {

    private val http = HttpClient.newHttpClient()
    private fun get(port: Int, path: String) =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(port: Int, path: String, body: String) =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://127.0.0.1:$port")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private val viewer = Paths.get(System.getProperty("user.dir")).parent.resolve("examples/web-viewer")

    private fun withViewer(store: ConflictStore, block: suspend (SvodEngine, Int) -> Unit) = runBlocking {
        assertTrue(Files.isRegularFile(viewer.resolve("index.html")), "bundled viewer at $viewer")
        val vault = Files.createTempDirectory("svod-viewer-conflicts-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SvodEngine.open(vault, scope)
        val index = IndexService(vault, vault.resolve(".svod").resolve("index"), NoneEmbedder).start()
        engine.onCommit { index.onCommit(it) }
        val api = AppApiServer(
            engine, index, EventBus(), conflicts = store,
            config = AppApiServer.Config(webViewerPath = viewer.toString()),
        ).start(0)
        try {
            block(engine, api.port)
        } finally {
            api.stop(); index.close(); engine.close()
        }
    }

    @Test
    fun `the bundled viewer ships the conflicts pane`(): Unit = withViewer(ConflictStore()) { _, port ->
        val page = get(port, "/").body()
        for (s in listOf("data-svod-conflicts", "id=\"conflictList\"", "id=\"conflictCount\"", "id=\"conflictRefresh\"", "id=\"conflictMsg\"", "Sync conflicts")) {
            assertTrue(page.contains(s), "index.html must contain $s")
        }
        val js = get(port, "/app.js").body()
        for (s in listOf("/api/v1/conflicts", "/api/v1/conflicts/resolve", "keepMine", "acceptIncoming", "acknowledgeSecrets",
            "secrets_detected", "sync.finished", "data-conflict-action", "remainingConflicts")) {
            assertTrue(js.contains(s), "app.js must contain $s")
        }
        val css = get(port, "/style.css").body()
        for (s in listOf(".conflicts-pane", ".merge3", ".merge-edit", ".conflict-actions")) {
            assertTrue(css.contains(s), "style.css must contain $s")
        }
    }

    @Test
    fun `the pane reads the fields the conflicts endpoint serves`(): Unit {
        val store = ConflictStore()
        store.record("notes/plan.md", "base text", "our text", null, listOf("body"), theirsBlob = "b1")
        withViewer(store) { _, port ->
            val r = get(port, "/api/v1/conflicts")
            assertEquals(200, r.statusCode(), r.body())
            val entry = Json.parseToJsonElement(r.body()).jsonObject["conflicts"]!!.jsonArray.single().jsonObject
            assertEquals("notes/plan.md", entry["path"]!!.jsonPrimitive.content)
            assertEquals(listOf("body"), entry["reasons"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("base text", entry["base"]!!.jsonPrimitive.content)
            assertEquals("our text", entry["ours"]!!.jsonPrimitive.content)
            assertTrue(entry["theirs"] == null || entry["theirs"] == JsonNull, "theirs is absent: $entry")
            assertTrue(entry["ts"]!!.jsonPrimitive.long > 0)
            assertEquals("false", entry["quarantined"]!!.jsonPrimitive.content)

            // The pane reads exactly these names, and shows a null side as absent.
            val js = get(port, "/app.js").body()
            for (s in listOf("j.conflicts", ".reasons", ".base", ".ours", ".theirs", ".ts", ".quarantined", "(absent on this side)")) {
                assertTrue(js.contains(s), "app.js must read $s")
            }
        }
    }

    @Test
    fun `the pane's resolve requests succeed same-origin`(): Unit {
        val store = ConflictStore()
        withViewer(store) { engine, port ->
            val author = Author("ui", "ui@svod.local")
            engine.write("notes/merge.md", "ours line\n", null, author)
            store.record("notes/merge.md", "base line\n", "ours line\n", "theirs line\n", listOf("body"), theirsBlob = "b1")
            store.record("notes/keep.md", "b", "mine", "theirs", listOf("body"), theirsBlob = "b2")
            store.record("notes/take.md", "b", "mine", "theirs", listOf("body"), theirsBlob = "b3")

            val merged = "ours line\ntheirs line\n"
            val edited = post(port, "/api/v1/conflicts/resolve",
                """{"path":"notes/merge.md","resolution":"content","content":${Json.encodeToString(String.serializer(), merged)}}""")
            assertEquals(200, edited.statusCode(), edited.body())
            val ej = Json.parseToJsonElement(edited.body()).jsonObject
            assertEquals("content", ej["resolution"]!!.jsonPrimitive.content)
            assertEquals("2", ej["remainingConflicts"]!!.jsonPrimitive.content)
            assertEquals(merged, engine.read("notes/merge.md")!!.text)

            val kept = post(port, "/api/v1/conflicts/resolve", """{"path":"notes/keep.md","resolution":"keepMine"}""")
            assertEquals(200, kept.statusCode(), kept.body())
            assertEquals("1", Json.parseToJsonElement(kept.body()).jsonObject["remainingConflicts"]!!.jsonPrimitive.content)

            val taken = post(port, "/api/v1/conflicts/resolve", """{"path":"notes/take.md","resolution":"acceptIncoming"}""")
            assertEquals(200, taken.statusCode(), taken.body())
            assertEquals("0", Json.parseToJsonElement(taken.body()).jsonObject["remainingConflicts"]!!.jsonPrimitive.content)

            // A second click on a resolved row is a 404, which the pane treats as "already resolved".
            val again = post(port, "/api/v1/conflicts/resolve", """{"path":"notes/keep.md","resolution":"keepMine"}""")
            assertEquals(404, again.statusCode(), again.body())

            val list = Json.parseToJsonElement(get(port, "/api/v1/conflicts").body()).jsonObject
            assertTrue(list["conflicts"]!!.jsonArray.isEmpty(), list.toString())

            // And the pane builds these bodies: resolution always, content only for an edited merge.
            val js = get(port, "/app.js").body()
            for (s in listOf("resolution", "body.content", "body.acknowledgeSecrets", "\"Content-Type\": \"application/json\"")) {
                assertTrue(js.contains(s), "app.js must contain $s")
            }
        }
    }
}
