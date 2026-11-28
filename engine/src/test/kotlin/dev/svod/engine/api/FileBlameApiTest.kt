package dev.svod.engine.api

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.events.EventBus
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** `GET /api/v1/file/blame`: line-level attribution of a note at HEAD. */
class FileBlameApiTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `blame returns each line with the commit that last changed it`() {
        ApiFixture.create().use { fx ->
            val p = ApiFixture.enc("n/doc.md")
            val created = fx.put("/api/v1/file?path=$p", """{"content":"one\ntwo\nthree\n"}""")
            assertEquals(200, created.statusCode(), created.body())
            val c1 = json(created.body())["commit"]!!.jsonPrimitive.content
            val rev = json(fx.get("/api/v1/file?path=$p").body())["revision"]!!.jsonPrimitive.content
            val edited = fx.put("/api/v1/file?path=$p", """{"content":"one\nTWO\nthree\n","expectedRevision":"$rev"}""")
            assertEquals(200, edited.statusCode(), edited.body())
            val c2 = json(edited.body())["commit"]!!.jsonPrimitive.content
            assertNotEquals(c1, c2)

            val r = fx.get("/api/v1/file/blame?path=$p")
            assertEquals(200, r.statusCode(), r.body())
            val body = json(r.body())
            assertEquals("n/doc.md", body["path"]!!.jsonPrimitive.content)
            val lines = body["lines"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(1, 2, 3), lines.map { it["line"]!!.jsonPrimitive.int })
            assertEquals(listOf("one", "TWO", "three"), lines.map { it["text"]!!.jsonPrimitive.content })
            assertEquals(listOf(c1, c2, c1), lines.map { it["commit"]!!.jsonPrimitive.content })
            lines.forEach {
                assertTrue(it["author"]!!.jsonPrimitive.content.isNotEmpty())
                assertTrue(it.containsKey("email"))
                assertTrue(it["epochSeconds"]!!.jsonPrimitive.long > 0)
            }
        }
    }

    @Test
    fun `missing path is 400 and an unknown note is 404`() {
        ApiFixture.create().use { fx ->
            assertEquals(400, fx.get("/api/v1/file/blame").statusCode())
            assertEquals(404, fx.get("/api/v1/file/blame?path=${ApiFixture.enc("nope.md")}").statusCode())
        }
    }

    @Test
    fun `a vault without a grant is hidden as 404`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val roots = listOf("a", "b").associateWith { Files.createTempDirectory("svod-blame-$it-") }
        val engines = roots.mapValues { (_, root) -> SvodEngine.open(root, scope) }
        val indexes = roots.mapValues { (_, root: Path) -> IndexService(root, root.resolve(".svod").resolve("index"), NoneEmbedder).start() }
        fun view(id: String) = object : VaultView {
            override val id = id
            override val name = id
            override val engine = engines.getValue(id)
            override val index = indexes.getValue(id)
            override val conflicts = null
            override fun syncStatus(): SyncStatusDto? = null
        }
        val views = mapOf("a" to view("a"), "b" to view("b"))
        val router = object : VaultRouter {
            override fun ids() = listOf("a", "b")
            override fun defaultId() = "a"
            override fun resolve(id: String?): VaultView? = if (id == null) views["a"] else views[id]
            override fun all() = views.values.toList()
        }
        val users = UserRegistry(listOf(
            UserRegistry.UserSpec("k-reader-a", "reader", "Reader", "reader@example.test", grants = mapOf("a" to VaultRole.READER)),
        ))
        val running = AppApiServer(vaults = router, eventBus = EventBus(), config = AppApiServer.Config(localAdmin = false), users = users).start(0)
        try {
            val ui = Author("Ui", "ui@example.test")
            runBlocking {
                assertTrue(engines.getValue("a").write("x.md", "a line", null, ui) is WriteOutcome.Success)
                assertTrue(engines.getValue("b").write("x.md", "b line", null, ui) is WriteOutcome.Success)
            }
            val http = HttpClient.newHttpClient()
            fun get(path: String) = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.port}$path")).header("Authorization", "Bearer k-reader-a").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val ok = get("/api/v1/file/blame?path=x.md")
            assertEquals(200, ok.statusCode(), ok.body())
            assertEquals("a line", json(ok.body())["lines"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
            val hidden = get("/api/v1/file/blame?vault=b&path=x.md")
            assertEquals(404, hidden.statusCode(), hidden.body())
            assertEquals(get("/api/v1/file/blame?vault=zzz&path=x.md").body(), hidden.body())
        } finally {
            running.stop()
            indexes.values.forEach { it.close() }
            engines.values.forEach { it.close() }
        }
    }
}
