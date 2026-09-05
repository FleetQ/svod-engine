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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    private class Fixture(localAdmin: Boolean = true, activityIntervalMs: Long = 60_000) : AutoCloseable {
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
        val activityFile: Path = secretsDir.resolve("user-activity.json")
        val activity = UserActivity(activityFile, minIntervalMs = activityIntervalMs)
        val audit = ApiAuditLog(secretsDir.resolve("audit-api.log"))
        private val running = AppApiServer(
            vaults = router, eventBus = eventBus,
            config = AppApiServer.Config(localAdmin = localAdmin),
            users = registry, userAdmin = users, secrets = SecretStore(secretsDir),
            activity = activity, audit = audit,
        ).start(0)
        val port get() = running.port
        private val http = JHttpClient.newHttpClient()

        fun req(method: String, path: String, key: String? = null, body: String? = null, contentType: String = "application/json"): HttpResponse<String> {
            val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).header("Content-Type", contentType)
            if (key != null) b.header("Authorization", "Bearer $key")
            b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        }
        fun get(path: String, key: String? = null) = req("GET", path, key)

        /**
         * A raw GET over a socket: java.net.http refuses to set `Host`, and HTTP/1.0 is the only
         * way to send no Host at all. Returns the status code.
         */
        fun rawGet(path: String, host: String?, key: String? = null, origin: String? = null): Int = java.net.Socket("127.0.0.1", port).use { sock ->
            val lines = mutableListOf(if (host == null) "GET $path HTTP/1.0" else "GET $path HTTP/1.1")
            if (host != null) lines += "Host: $host"
            if (key != null) lines += "Authorization: Bearer $key"
            if (origin != null) lines += "Origin: $origin"
            lines += "Connection: close"
            sock.getOutputStream().write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray())
            sock.getOutputStream().flush()
            val status = sock.getInputStream().bufferedReader().readLine() ?: ""
            status.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        }
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
    fun `grants scope vaults - an ungranted vault is indistinguishable from an unknown one`(): Unit = runBlocking {
        Fixture().use { fx ->
            val ungranted = fx.get("/api/v1/tree?vault=b", key = "k-editor")
            val unknown = fx.get("/api/v1/tree?vault=nope", key = "k-editor")
            assertEquals(404, ungranted.statusCode(), "a vault without a grant must not reveal that it exists")
            assertEquals(404, unknown.statusCode())
            assertEquals(unknown.body(), ungranted.body(), "same body, or the difference is the leak")
            assertEquals(403, fx.write("a", "ro.md", "# x", key = "k-reader").statusCode(), "a reader's write stays 403: they know the vault")
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
    fun `a doubled slash or an encoded segment cannot slip past the auth check`(): Unit = runBlocking {
        Fixture(localAdmin = false).use { fx ->
            // Ktor routes `//api/v1/vaults` and `/api/v1/%76aults` to the real handlers; the
            // interceptor must see the same path the router does, or neither check runs.
            assertEquals(400, fx.get("//api/v1/vaults").statusCode(), "empty segment, no key")
            assertEquals(400, fx.get("/api/v1/%76aults").statusCode(), "encoded segment, no key")
            assertEquals(400, fx.req("POST", "//api/v1/users", body = """{"userId":"evil","name":"E","admin":true}""").statusCode())
            assertEquals(400, fx.req("POST", "/api/v1/%75sers", "k-editor", """{"userId":"evil","name":"E","admin":true}""").statusCode())
            assertEquals(200, fx.get("/api/v1/users", key = "k-admin").statusCode(), "the canonical path still works")
            assertFalse(fx.get("/api/v1/users", key = "k-admin").body().contains("evil"), "no user was created")
        }
    }

    @Test
    fun `a move never rewrites links in a vault the mover cannot write`(): Unit = runBlocking {
        Fixture().use { fx ->
            assertEquals(200, fx.write("a", "old.md", "# old", key = "k-admin").statusCode())
            assertEquals(200, fx.write("b", "ref.md", "see [[a:old]]", key = "k-admin").statusCode())
            val headB = fx.b.engine.head()
            // Мария is EDITOR on a only.
            val rev1 = fx.a.engine.read("old.md")!!.revision
            assertEquals(200, fx.req("POST", "/api/v1/file/move?vault=a", "k-editor", """{"from":"old.md","to":"new.md","expectedRevision":"$rev1"}""").statusCode())
            delay(300)
            assertEquals(headB, fx.b.engine.head(), "vault b must not have been written by a user without a grant on it")
            assertEquals("see [[a:old]]", fx.b.engine.read("ref.md")!!.text)
            // The admin CAN write b, so the same move by them relinks it.
            val refRev = fx.b.engine.read("ref.md")!!.revision
            assertEquals(200, fx.req("PUT", "/api/v1/file?vault=b&path=ref.md", "k-admin", """{"content":"see [[a:new]]","expectedRevision":"$refRev"}""").statusCode())
            val rev2 = fx.a.engine.read("new.md")!!.revision
            assertEquals(200, fx.req("POST", "/api/v1/file/move?vault=a", "k-admin", """{"from":"new.md","to":"newer.md","expectedRevision":"$rev2"}""").statusCode())
            withTimeout(5000) { while (fx.b.engine.read("ref.md")!!.text == "see [[a:new]]") delay(50) }
            assertEquals("see [[a:newer]]", fx.b.engine.read("ref.md")!!.text)
        }
    }

    @Test
    fun `cross-vault backlinks come only from vaults the caller can read`(): Unit = runBlocking {
        Fixture().use { fx ->
            assertEquals(200, fx.write("a", "target.md", "# t", key = "k-admin").statusCode())
            assertEquals(200, fx.write("b", "secret-plan.md", "see [[a:target]]", key = "k-admin").statusCode())
            val asAdmin = fx.get("/api/v1/file/links?vault=a&path=target.md", key = "k-admin").body()
            assertTrue("b:secret-plan.md" in asAdmin, asAdmin)
            val asReader = fx.get("/api/v1/file/links?vault=a&path=target.md", key = "k-reader").body()
            assertFalse("secret-plan" in asReader, "a note path from vault b reached a reader of a only: $asReader")
        }
    }

    // ---- sprint 2a: hardening ----

    @Test
    fun `a keyless loopback request must name a loopback Host - DNS rebinding`(): Unit = runBlocking {
        Fixture().use { fx ->
            assertEquals(200, fx.get("/api/v1/tree").statusCode(), "the app's own requests carry Host: 127.0.0.1:port")
            assertEquals(200, fx.rawGet("/api/v1/tree", host = "localhost:${fx.port}"))
            assertEquals(200, fx.rawGet("/api/v1/tree", host = "[::1]:${fx.port}"))
            assertEquals(200, fx.rawGet("/api/v1/tree", host = "127.0.0.1"))
            assertEquals(401, fx.rawGet("/api/v1/tree", host = "evil.example:${fx.port}"), "a rebound DNS name is not the local UI")
            assertEquals(401, fx.rawGet("/api/v1/tree", host = "evil.example"))
            assertEquals(401, fx.rawGet("/api/v1/tree", host = "127.0.0.1.evil.example"))
            assertEquals(200, fx.rawGet("/api/v1/tree", host = "evil.example", key = "k-editor"), "a keyed request does not depend on Host")
            assertEquals(401, fx.rawGet("/api/v1/tree", host = null), "no Host header at all is not loopback either")
        }
    }

    @Test
    fun `a keyless request from a foreign Origin is not the local UI - cross-origin WebSocket`(): Unit = runBlocking {
        Fixture().use { fx ->
            val h = "127.0.0.1:${fx.port}"
            // A page on evil.example opening ws://127.0.0.1/api/v1/events: Host is legitimately loopback, Origin is not.
            assertEquals(401, fx.rawGet("/api/v1/tree", host = h, origin = "https://evil.example"))
            assertEquals(401, fx.rawGet("/api/v1/tree", host = h, origin = "null"), "an opaque origin (sandboxed iframe, file://) is not ours either")
            assertEquals(200, fx.rawGet("/api/v1/tree", host = h, origin = "http://$h"), "the engine's own web viewer")
            assertEquals(200, fx.rawGet("/api/v1/tree", host = "localhost:${fx.port}", origin = "http://localhost:${fx.port}"))
            assertEquals(401, fx.rawGet("/api/v1/tree", host = h, origin = "http://127.0.0.1:9999"), "another loopback PORT is another origin (a dev server)")
            assertEquals(401, fx.rawGet("/api/v1/tree", host = h, origin = "http://localhost:${fx.port}"), "host must match the request's Host exactly")
            assertEquals(200, fx.rawGet("/api/v1/tree", host = h), "native clients send no Origin")
            assertEquals(200, fx.rawGet("/api/v1/tree", host = h, key = "k-editor", origin = "https://evil.example"), "a keyed request does not depend on Origin")
            // The real thing: a WebSocket upgrade with a foreign Origin and no key must be refused.
            val client = HttpClient(CIO) { install(WebSockets) }
            val r = runCatching {
                withTimeout(3000) {
                    client.webSocket(host = "127.0.0.1", port = fx.port, path = "/api/v1/events", request = { header("Origin", "https://evil.example") }) { incoming.receive() }
                }
            }
            assertTrue(r.isFailure, "cross-origin keyless WebSocket must not be upgraded")
            client.close()
        }
    }

    @Test
    fun `keyed requests are audited - the local UI is not - and no line ever holds a key`(): Unit = runBlocking {
        Fixture().use { fx ->
            assertEquals(200, fx.get("/api/v1/tree?vault=a", key = "k-editor").statusCode())
            assertEquals(200, fx.write("a", "audited.md", "# a", key = "k-editor").statusCode())
            assertEquals(200, fx.get("/api/v1/users", key = "k-admin").statusCode())
            assertEquals(403, fx.get("/api/v1/users", key = "k-reader").statusCode())
            repeat(3) { assertEquals(200, fx.get("/api/v1/tree").statusCode()) }   // local UI
            assertEquals(401, fx.get("/api/v1/tree", key = "svk_guess").statusCode())   // a refused attempt IS audited
            delay(200)
            val entries = fx.audit.entries()
            assertEquals(5, entries.size, entries.toString())
            assertEquals(AppApiAuth.ANONYMOUS, entries[4].userId); assertEquals(401, entries[4].status)
            val e0 = entries[0]
            assertEquals("maria", e0.userId); assertEquals("GET", e0.method); assertEquals("/api/v1/tree", e0.path); assertEquals("a", e0.vault); assertEquals(200, e0.status)
            assertEquals("PUT", entries[1].method); assertEquals("/api/v1/file", entries[1].path)
            assertEquals("boss", entries[2].userId); assertEquals(200, entries[2].status)
            assertEquals("ivan", entries[3].userId); assertEquals(403, entries[3].status)
            assertTrue(entries.none { it.userId == "local" }, "the loopback UI is not a person to audit")
            val raw = Files.readString(fx.secretsDir.resolve("audit-api.log"))
            assertFalse("k-editor" in raw || "k-admin" in raw || "k-reader" in raw || "svk_" in raw, raw)
        }
    }

    @Test
    fun `a request that throws is still audited, as a 500`(): Unit = runBlocking {
        Fixture().use { fx ->
            // A move without expectedRevision of a note that does not exist makes the engine throw? No —
            // it answers 404. Use a body the route cannot parse: receive<MoveRequestDto>() throws inside proceed().
            val r = fx.req("POST", "/api/v1/file/move?vault=a", "k-editor", """{"from": 5}""")
            delay(200)
            val e = fx.audit.entries().last()
            assertEquals("maria", e.userId); assertEquals("/api/v1/file/move", e.path)
            assertEquals(r.statusCode(), e.status, "the audit line carries the status the client saw: $e")
            assertEquals(400, e.status)
            // A body the route cannot even negotiate: Ktor answers 415, and so must the audit line.
            val r2 = fx.req("POST", "/api/v1/file/move?vault=a", "k-editor", "from=old", contentType = "text/plain")
            delay(200)
            val e2 = fx.audit.entries().last()
            assertEquals(415, r2.statusCode()); assertEquals(r2.statusCode(), e2.status, "$e2")
        }
    }

    @Test
    fun `a member sees the backup schedule but not the remote, peers or host id`(): Unit = runBlocking {
        Fixture().use { fx ->
            val asReader = fx.get("/api/v1/sync/config?vault=a", key = "k-reader").body()
            assertFalse("\"backupRemote\"" in asReader && "github" in asReader, asReader)
            assertFalse("\"hostId\":\"" in asReader, "host identity is the admin's: $asReader")
            assertTrue("\"syncPeers\":[]" in asReader, asReader)
            assertEquals(200, fx.get("/api/v1/sync/config?vault=a", key = "k-admin").statusCode())
        }
    }

    @Test
    fun `a refused request is logged with its origin and reason but never the key`(): Unit = runBlocking {
        Fixture(localAdmin = false).use { fx ->
            val logger = org.slf4j.LoggerFactory.getLogger(AppApiAuth::class.java) as ch.qos.logback.classic.Logger
            val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                assertEquals(401, fx.get("/api/v1/tree", key = "svk_definitely-wrong").statusCode())
                assertEquals(401, fx.get("/api/v1/tree").statusCode())
                assertEquals(403, fx.get("/api/v1/users", key = "k-reader").statusCode())
                val lines = appender.list.map { it.formattedMessage }
                assertEquals(3, lines.size, lines.toString())
                assertTrue(lines[0].contains("127.0.0.1") && lines[0].contains("/api/v1/tree") && lines[0].contains("key not accepted"), lines[0])
                assertTrue(lines[1].contains("no key"), lines[1])
                assertTrue(lines[2].contains("ivan is not an admin"), lines[2])
                assertFalse(lines.any { "definitely-wrong" in it || "k-reader" in it }, "a key value must never reach a log line: $lines")
            } finally { logger.detachAppender(appender) }
        }
    }

    @Test
    fun `lastUsedAt follows authentication and survives a restart`(): Unit = runBlocking {
        Fixture(activityIntervalMs = 0).use { fx ->
            fun lastUsed(body: String, id: String): String? {
                val users = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["users"]!!.jsonArray
                val u = users.map { it.jsonObject }.first { it["userId"]!!.jsonPrimitive.content == id }
                return u["lastUsedAt"]?.jsonPrimitive?.contentOrNull
            }
            val before = fx.get("/api/v1/users", key = "k-admin").body()
            assertEquals(null, lastUsed(before, "maria"), "never used ⇒ no lastUsedAt: $before")
            assertEquals(200, fx.get("/api/v1/tree?vault=a", key = "k-editor").statusCode())
            val after = fx.get("/api/v1/users", key = "k-admin").body()
            val iso = lastUsed(after, "maria") ?: error("maria authenticated ⇒ lastUsedAt: $after")
            val ts = java.time.Instant.parse(iso).toEpochMilli()
            assertTrue(System.currentTimeMillis() - ts < 10_000, "lastUsedAt is now: $iso")
            assertEquals(null, lastUsed(after, "ivan"), "ivan has not authenticated: $after")
            val me = fx.get("/api/v1/me", key = "k-editor").body()
            assertTrue("\"lastUsedAt\"" in me, me)
            assertFalse("\"lastUsedAt\"" in fx.get("/api/v1/me").body(), "the local identity has no lastUsedAt")
            // Persisted: a fresh UserActivity over the same file knows the latest value (the /me call above touched maria again).
            assertEquals(fx.activity.lastUsed("maria"), UserActivity(fx.activityFile).lastUsed("maria"))
        }
    }

    @Test
    fun `lastUsedAt is persisted at most once per interval`(): Unit = runBlocking {
        Fixture(activityIntervalMs = 60_000).use { fx ->
            assertEquals(200, fx.get("/api/v1/tree?vault=a", key = "k-editor").statusCode())
            val first = Files.readString(fx.activityFile)
            delay(50)
            assertEquals(200, fx.get("/api/v1/tree?vault=a", key = "k-editor").statusCode())
            assertEquals(first, Files.readString(fx.activityFile), "second use inside the interval must not rewrite the file")
            val inMemory = fx.activity.lastUsed("maria")!!
            val onDisk = Regex("\"maria\":(\\d+)").find(first)!!.groupValues[1].toLong()
            assertTrue(inMemory >= onDisk, "memory is exact, disk is throttled")
        }
    }

    @Test
    fun `a member does not see server paths or endpoints`(): Unit = runBlocking {
        Fixture().use { fx ->
            val asAdmin = fx.get("/api/v1/settings?vault=a", key = "k-admin").body()
            assertTrue("\"vaultPath\":\"${fx.a.root}\"" in asAdmin, asAdmin)
            val asReader = fx.get("/api/v1/settings?vault=a", key = "k-reader").body()
            assertTrue("\"vaultPath\":\"\"" in asReader, asReader)
            assertTrue("\"host\":\"\"" in asReader, asReader)
            assertFalse(fx.a.root.toString() in asReader, "the server path leaked to a reader: $asReader")
            assertTrue("\"apiVersion\"" in asReader, "the rest of the settings stay: $asReader")

            val srcDir = Files.createTempDirectory("svod-src-")
            Files.writeString(srcDir.resolve("note.md"), "# from outside")
            val reg = fx.req("POST", "/api/v1/sources?vault=a", "k-admin", """{"path":"${srcDir}","into":"ext"}""")
            assertEquals(200, reg.statusCode(), reg.body())
            val adminSources = fx.get("/api/v1/sources?vault=a", key = "k-admin").body()
            assertTrue(srcDir.toString() in adminSources, adminSources)
            val readerSources = fx.get("/api/v1/sources?vault=a", key = "k-reader").body()
            assertFalse(srcDir.parent.toString() in readerSources, "server path leaked to a reader: $readerSources")
            assertTrue("\"path\":\"${srcDir.fileName}\"" in readerSources, "the basename stays so the source is recognisable: $readerSources")
        }
    }

    @Test
    fun `metrics need an ADMIN key on a shared engine and none on a single-user one`(): Unit = runBlocking {
        Fixture(localAdmin = false).use { fx ->
            assertEquals(401, fx.get("/metrics").statusCode())
            assertEquals(403, fx.get("/metrics", key = "k-reader").statusCode(), "metrics list every vault id: not for a member")
            assertEquals(200, fx.get("/metrics", key = "k-admin").statusCode())
            assertEquals(400, fx.get("/%61pi/v1/users", key = "k-admin").statusCode())
            delay(200)
            val audited = fx.audit.entries().map { "${it.userId} ${it.method} ${it.path} ${it.status}" }
            assertTrue("${AppApiAuth.ANONYMOUS} GET /metrics 401" in audited, audited.toString())
            assertTrue("ivan GET /metrics 403" in audited, audited.toString())
            assertTrue("boss GET /metrics 200" in audited, "the protected endpoint leaves a record: $audited")
            assertTrue("${AppApiAuth.ANONYMOUS} GET /api/v1/users 400" in audited, "an encoded prefix is audited under its canonical path: $audited")
            assertEquals(200, fx.get("/health").statusCode(), "health stays open: no vault data")
            assertEquals(200, fx.get("/ready").statusCode())
        }
        Fixture().use { fx -> assertEquals(200, fx.get("/metrics").statusCode()) }
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
            assertEquals(404, fx.get("/api/v1/tree?vault=a", key = key).statusCode(), "no grant ⇒ the vault does not exist for them")

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
