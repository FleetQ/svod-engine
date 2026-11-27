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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `GET /api/v1/changes`: the vault-wide change feed over the App API, with personal keys and two vaults. */
class ChangesApiTest {

    private class Vault(val id: String, scope: CoroutineScope) : AutoCloseable {
        val root: Path = Files.createTempDirectory("svod-changes-$id-")
        val engine: SvodEngine = SvodEngine.open(root, scope)
        val index: IndexService = IndexService(root, root.resolve(".svod").resolve("index"), NoneEmbedder).start()
        val view = object : VaultView {
            override val id = this@Vault.id
            override val name = this@Vault.id
            override val engine = this@Vault.engine
            override val index = this@Vault.index
            override val conflicts = null
            override fun syncStatus(): SyncStatusDto? = null
        }
        override fun close() { index.close(); engine.close() }
    }

    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = Vault("a", scope)
        val b = Vault("b", scope)
        private val router = object : VaultRouter {
            override fun ids() = listOf("a", "b")
            override fun defaultId() = "a"
            override fun resolve(id: String?): VaultView? = when (id) { null, "a" -> a.view; "b" -> b.view; else -> null }
            override fun all() = listOf(a.view, b.view)
        }
        private val users = UserRegistry(listOf(
            UserRegistry.UserSpec("k-reader-a", "ivan", "Ivan", "ivan@example.test", grants = mapOf("a" to VaultRole.READER)),
            UserRegistry.UserSpec("k-reader-b", "maria", "Maria", "maria@example.test", grants = mapOf("b" to VaultRole.READER)),
        ))
        private val running = AppApiServer(
            vaults = router, eventBus = EventBus(),
            config = AppApiServer.Config(localAdmin = false),
            users = users,
        ).start(0)
        private val http = HttpClient.newHttpClient()

        fun get(path: String, key: String): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.port}$path")).header("Authorization", "Bearer $key").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        override fun close() { running.stop(); a.close(); b.close() }
    }

    private val ui = Author("Ui", "ui@example.test")
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun commits(body: JsonObject) = body["changes"]!!.jsonArray.map { it.jsonObject["commit"]!!.jsonPrimitive.content }

    @Test
    fun `a reader of the vault gets the feed, others do not`() {
        Fixture().use { fx ->
            val w = runBlocking { fx.a.engine.write("n/a.md", "# A", null, ui) } as WriteOutcome.Success
            val r = fx.get("/api/v1/changes", "k-reader-a")
            assertEquals(200, r.statusCode(), r.body())
            val body = json(r.body())
            assertEquals(w.commit, body["head"]!!.jsonPrimitive.content)
            val top = body["changes"]!!.jsonArray.first().jsonObject
            assertEquals(w.commit, top["commit"]!!.jsonPrimitive.content)
            assertEquals("Ui", top["author"]!!.jsonPrimitive.content)
            val p = top["paths"]!!.jsonArray.single().jsonObject
            assertEquals("n/a.md", p["path"]!!.jsonPrimitive.content)
            assertEquals("add", p["kind"]!!.jsonPrimitive.content)

            // no grant on the vault: refused the same way as an unknown vault (AppApiAuth hides which ids exist)
            val refused = fx.get("/api/v1/changes", "k-reader-b")
            assertEquals(404, refused.statusCode(), refused.body())
            assertEquals(404, fx.get("/api/v1/changes?vault=b", "k-reader-a").statusCode())
        }
    }

    @Test
    fun `vault picks the vault, since garbage is 400`() {
        Fixture().use { fx ->
            val wa = runBlocking { fx.a.engine.write("only-a.md", "a", null, ui) } as WriteOutcome.Success
            val wb = runBlocking { fx.b.engine.write("only-b.md", "b", null, ui) } as WriteOutcome.Success
            val r = fx.get("/api/v1/changes?vault=b", "k-reader-b")
            assertEquals(200, r.statusCode(), r.body())
            val ids = commits(json(r.body()))
            assertTrue(wb.commit in ids)
            assertTrue(wa.commit !in ids)
            assertEquals(400, fx.get("/api/v1/changes?vault=b&since=garbage", "k-reader-b").statusCode())
            assertEquals(400, fx.get("/api/v1/changes?vault=b&before=garbage", "k-reader-b").statusCode())
        }
    }

    @Test
    fun `paging via before and nextCursor`() {
        Fixture().use { fx ->
            val ids = runBlocking { (1..4).map { (fx.a.engine.write("p$it.md", "p$it", null, ui) as WriteOutcome.Success).commit } }.reversed()
            val first = json(fx.get("/api/v1/changes?max=2", "k-reader-a").body())
            assertEquals(ids.take(2), commits(first))
            val cursor = first["nextCursor"]!!.jsonPrimitive.contentOrNull!!
            assertEquals(ids[1], cursor)
            val second = json(fx.get("/api/v1/changes?max=2&before=$cursor", "k-reader-a").body())
            assertEquals(ids.subList(2, 4), commits(second))
            val sinceOld = json(fx.get("/api/v1/changes?since=${ids[2]}", "k-reader-a").body())
            assertEquals(ids.take(2), commits(sinceOld))
        }
    }
}
