package dev.svod.engine.api

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.lifecycle.ConfigStore
import dev.svod.engine.lifecycle.SecretStore
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.lifecycle.UserController
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient as JHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-0019: people reach the App API with personal keys, per-vault roles, and become the git
 * author. Each test spins a two-vault server (`a` default, `b`) with an admin, an editor on `a`
 * and a reader on `a`.
 */
class PrincipalAuthTest {

    private class Vault(val id: String, scope: CoroutineScope) : AutoCloseable {
        val root: Path = Files.createTempDirectory("svod-auth-$id-")
        val engine: SvodEngine = SvodEngine.open(root, scope)
        val index: IndexService = IndexService(root, root.resolve(".svod").resolve("index"), NoneEmbedder).start()
        init { engine.onCommit { index.onCommit(it) } }
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

    private class Fixture(localAdmin: Boolean = true) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = Vault("a", scope)
        val b = Vault("b", scope)
        val eventBus = EventBus()
        val router = object : VaultRouter {
            override fun ids() = listOf("a", "b")
            override fun defaultId() = "a"
            override fun resolve(id: String?): VaultView? = when (id) { null, "a" -> a.view; "b" -> b.view; else -> null }
            override fun all() = listOf(a.view, b.view)
        }
        val secretsDir: Path = Files.createTempDirectory("svod-auth-secrets-")
        // Users live in CONFIG (file: key refs), exactly as in production: the controller rebuilds
        // the registry from config after every mutation, so a registry seeded out of band would
        // silently lose its users on the first create — which is how this fixture first failed.
        private fun keyRef(id: String, value: String): String {
            val f = secretsDir.resolve("user-$id.key"); Files.writeString(f, value); return "file:$f"
        }
        val config = SvodConfig(
            vaults = listOf(SvodConfig.VaultSettings("a", a.root.toString()), SvodConfig.VaultSettings("b", b.root.toString())),
            defaultVault = "a",
            users = listOf(
                SvodConfig.UserSettings("boss", "Boss", "boss@co", keyRef("boss", "k-admin"), admin = true),
                SvodConfig.UserSettings("maria", "Мария", "maria@co", keyRef("maria", "k-editor"), grants = listOf(SvodConfig.VaultGrant("a", "EDITOR"))),
                SvodConfig.UserSettings("ivan", "Иван", "ivan@co", keyRef("ivan", "k-reader"), grants = listOf(SvodConfig.VaultGrant("a", "READER"))),
            ),
        )
        val configStore = ConfigStore(config, null)
        val registry = UserRegistry(config.toUserSpecs())
        val users = UserController(configStore, registry, secretsDir)
        private val running = AppApiServer(
            vaults = router, eventBus = eventBus,
            config = AppApiServer.Config(localAdmin = localAdmin),
            users = registry, userAdmin = users, secrets = SecretStore(secretsDir),
        ).start(0)
        val port get() = running.port
        private val http = JHttpClient.newHttpClient()

        fun req(method: String, path: String, key: String? = null, body: String? = null): HttpResponse<String> {
            val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).header("Content-Type", "application/json")
            if (key != null) b.header("Authorization", "Bearer $key")
            b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        }
        fun get(path: String, key: String? = null) = req("GET", path, key)
        fun write(vault: String, path: String, content: String, key: String?) =
            req("PUT", "/api/v1/file?vault=$vault&path=$path", key, """{"content":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), content)}}""")

        override fun close() { running.stop(); a.close(); b.close() }
    }

    private fun keyOf(body: String): String =
        Regex("\"key\":\"(svk_[A-Za-z0-9_-]+)\"").find(body)?.groupValues?.get(1) ?: error("no key in response: $body")

    @Test
    fun `loopback without a key is the local UI and writes as svod-ui`(): Unit = runBlocking {
        Fixture().use { fx ->
            assertEquals(200, fx.get("/api/v1/tree").statusCode())
            assertEquals(200, fx.write("a", "n.md", "# local", key = null).statusCode())
            assertEquals("svod-ui", fx.a.engine.history("n.md").first().authorName)
            val me = fx.get("/api/v1/me").body()
            assertTrue("\"local\":true" in me && "\"admin\":true" in me, me)
        }
    }

    @Test
    fun `localAdmin off - no key is 401 while the ops surface stays open`(): Unit = runBlocking {
        Fixture(localAdmin = false).use { fx ->
            assertEquals(401, fx.get("/api/v1/tree").statusCode())
            assertEquals(401, fx.get("/api/v1/vaults").statusCode())
            assertEquals(200, fx.get("/health").statusCode())
            assertEquals(200, fx.get("/ready").statusCode())
            assertEquals(401, fx.get("/api/v1/tree", key = "not-a-key").statusCode())
            assertEquals(200, fx.get("/api/v1/tree", key = "k-editor").statusCode())
        }
    }

    @Test
    fun `reader can read but a write is 403 and leaves HEAD alone`(): Unit = runBlocking {
        Fixture().use { fx ->
            fx.write("a", "seed.md", "# seed", key = "k-admin")
            val head = fx.a.engine.head()
            assertEquals(200, fx.get("/api/v1/file?vault=a&path=seed.md", key = "k-reader").statusCode())
            val r = fx.write("a", "seed.md", "# hacked", key = "k-reader")
            assertEquals(403, r.statusCode(), r.body())
            assertTrue("forbidden" in r.body())
            assertEquals(head, fx.a.engine.head(), "a forbidden write must not move HEAD")
        }
    }

    @Test
    fun `editor writes as the person, and the commit event names them`(): Unit = runBlocking {
        Fixture().use { fx ->
            val received = CopyOnWriteArrayList<String>()
            val client = HttpClient(CIO) { install(WebSockets) }
            val job = launch(Dispatchers.IO) {
                runCatching {
                    client.webSocket(host = "127.0.0.1", port = fx.port, path = "/api/v1/events", request = { header("Authorization", "Bearer k-admin") }) {
                        for (frame in incoming) if (frame is Frame.Text) received.add(frame.readText())
                    }
                }
            }
            delay(500)
            val r = fx.write("a", "by-maria.md", "# от Мария", key = "k-editor")
            assertEquals(200, r.statusCode(), r.body())
            val c = fx.a.engine.history("by-maria.md").first()
            assertEquals("Мария", c.authorName)
            assertEquals("maria@co", c.authorEmail)
            withTimeout(5000) { while (received.none { "\"commit.created\"" in it }) delay(50) }
            assertTrue(received.any { "\"commit.created\"" in it && "\"author\":\"Мария\"" in it }, received.toString())
            job.cancel(); client.close()
        }
    }

    @Test
    fun `grants scope vaults - an ungranted vault is 403 and the list is filtered with roles`(): Unit = runBlocking {
        Fixture().use { fx ->
            assertEquals(403, fx.get("/api/v1/tree?vault=b", key = "k-editor").statusCode())
            assertEquals(404, fx.get("/api/v1/tree?vault=nope", key = "k-editor").statusCode(), "unknown vault stays a 404")
            val mine = fx.get("/api/v1/vaults", key = "k-editor").body()
            assertTrue("\"id\":\"a\"" in mine && "\"role\":\"editor\"" in mine, mine)
            assertFalse("\"id\":\"b\"" in mine, mine)
            val reader = fx.get("/api/v1/vaults", key = "k-reader").body()
            assertTrue("\"role\":\"reader\"" in reader, reader)
            val all = fx.get("/api/v1/vaults", key = "k-admin").body()
            assertTrue("\"id\":\"a\"" in all && "\"id\":\"b\"" in all && "\"role\":\"admin\"" in all, all)
        }
    }

    @Test
    fun `engine-admin routes need admin`(): Unit = runBlocking {
        Fixture().use { fx ->
            assertEquals(403, fx.req("PUT", "/api/v1/settings/backup?vault=a", "k-editor", """{"remote":"x"}""").statusCode())
            assertEquals(403, fx.req("POST", "/api/v1/vaults", "k-editor", """{"id":"c"}""").statusCode())
            assertEquals(403, fx.get("/api/v1/users", key = "k-editor").statusCode())
            assertEquals(403, fx.get("/api/v1/agents", key = "k-reader").statusCode())
            assertEquals(200, fx.get("/api/v1/users", key = "k-admin").statusCode())
            assertEquals(200, fx.get("/api/v1/users").statusCode(), "the local UI is an admin")
        }
    }

    @Test
    fun `me describes the caller`(): Unit = runBlocking {
        Fixture().use { fx ->
            val me = fx.get("/api/v1/me", key = "k-editor").body()
            assertTrue("\"userId\":\"maria\"" in me && "\"admin\":false" in me && "\"local\":false" in me, me)
            assertTrue("{\"vault\":\"a\",\"role\":\"editor\"}" in me, me)
        }
    }

    @Test
    fun `federated search never returns a hit from a vault the caller cannot read`(): Unit = runBlocking {
        Fixture().use { fx ->
            fx.write("b", "secret.md", "# zebra plans\nzebra zebra", key = "k-admin")
            fx.write("a", "open.md", "# zebra notes\nzebra", key = "k-admin")
            fx.a.index.waitIdle(); fx.b.index.waitIdle()
            val mine = fx.get("/api/v1/search?q=zebra&across=true&mode=keyword", key = "k-editor").body()
            assertTrue("open.md" in mine, mine)
            assertFalse("secret.md" in mine, "a hit leaked from vault b: $mine")
            val all = fx.get("/api/v1/search?q=zebra&across=true&mode=keyword", key = "k-admin").body()
            assertTrue("secret.md" in all && "open.md" in all, all)
        }
    }

    @Test
    fun `events are filtered to readable vaults`(): Unit = runBlocking {
        Fixture().use { fx ->
            val received = CopyOnWriteArrayList<String>()
            val client = HttpClient(CIO) { install(WebSockets) }
            val job = launch(Dispatchers.IO) {
                runCatching {
                    client.webSocket(host = "127.0.0.1", port = fx.port, path = "/api/v1/events", request = { header("Authorization", "Bearer k-reader") }) {
                        for (frame in incoming) if (frame is Frame.Text) received.add(frame.readText())
                    }
                }
            }
            delay(500)
            fx.write("b", "hidden.md", "# b", key = "k-admin")
            fx.write("a", "visible.md", "# a", key = "k-admin")
            withTimeout(5000) { while (received.none { "visible.md" in it }) delay(50) }
            delay(300)
            assertTrue(received.any { "\"commit.created\"" in it && "visible.md" in it }, received.toString())
            assertFalse(received.any { "hidden.md" in it }, "an event for vault b reached a reader of a: $received")
            job.cancel(); client.close()
        }
    }

    @Test
    fun `a websocket without a key is refused when localAdmin is off`(): Unit = runBlocking {
        Fixture(localAdmin = false).use { fx ->
            val client = HttpClient(CIO) { install(WebSockets) }
            val r = runCatching {
                withTimeout(3000) {
                    client.webSocket(host = "127.0.0.1", port = fx.port, path = "/api/v1/events") { incoming.receive() }
                }
            }
            assertTrue(r.isFailure, "the upgrade must be rejected")
            client.close()
        }
    }

    @Test
    fun `admin creates a person over HTTP - the key works at once and is never listed`(): Unit = runBlocking {
        Fixture().use { fx ->
            val created = fx.req("POST", "/api/v1/users", "k-admin",
                """{"userId":"pesho","name":"Пешо","grants":[{"vault":"b","role":"editor"}]}""")
            assertEquals(201, created.statusCode(), created.body())
            val key = keyOf(created.body())
            assertFalse(key in fx.get("/api/v1/users", key = "k-admin").body(), "the raw key must never be listed")
            assertEquals(200, fx.write("b", "pesho.md", "# hi", key = key).statusCode())
            assertEquals("Пешо", fx.b.engine.history("pesho.md").first().authorName)
            assertEquals(403, fx.get("/api/v1/tree?vault=a", key = key).statusCode())

            val rotated = fx.req("POST", "/api/v1/users/pesho/key", "k-admin")
            val key2 = keyOf(rotated.body())
            assertNotEquals(key, key2)
            assertEquals(401, fx.get("/api/v1/tree?vault=b", key = key).statusCode(), "the old key must die immediately")
            assertEquals(200, fx.get("/api/v1/tree?vault=b", key = key2).statusCode())

            assertEquals(200, fx.req("DELETE", "/api/v1/users/pesho", "k-admin").statusCode())
            assertEquals(401, fx.get("/api/v1/tree?vault=b", key = key2).statusCode())
        }
    }
}
