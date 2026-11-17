package dev.svod.engine.lifecycle

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The embedder API must never hand a stored key, or an arbitrary file, to an endpoint the caller picks. */
class EmbedderKeyTest {

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun call(port: Int, method: String, path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    /** A fake OpenAI-compatible endpoint that records the Authorization header of every request. */
    private class FakeEmbedder : AutoCloseable {
        val auth = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { ex ->
                auth += ex.requestHeaders.getFirst("Authorization") ?: "<none>"
                ex.requestBody.readAllBytes()
                val body = if (ex.requestURI.path.endsWith("/models")) """{"data":[{"id":"m1"}]}"""
                else """{"data":[{"index":0,"embedding":[0.1,0.2,0.3]}]}"""
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private fun start(dir: Path, deny: Boolean, embedder: SvodConfig.EmbedderSettings = SvodConfig.EmbedderSettings(provider = "none")): SvodNode {
        val vault = Files.createDirectories(dir.resolve("vault"))
        val cfg = SvodConfig(
            vaultPath = vault.toString(), appApiPort = 0, mcpPort = 0,
            embedder = embedder,
            reranker = SvodConfig.RerankerSettings(provider = "none"),
            agents = listOf(SvodConfig.AgentSettings("t", "scribe", "WRITE")),
            denyPrivateRemotes = deny,
        )
        return SvodNode.start(cfg, configPath = dir.resolve("config.json"))
    }

    @AfterTest
    fun resetPin() = dev.svod.engine.sync.PinnedHttps.enable(false)

    @Test
    fun `a request naming another endpoint never gets the configured key`() {
        val dir = Files.createTempDirectory("svod-embkey-")
        val keyFile = dir.resolve("stored.key").also { Files.writeString(it, "STOREDKEY") }
        FakeEmbedder().use { legit -> FakeEmbedder().use { evil ->
            val node = start(dir, deny = false, SvodConfig.EmbedderSettings(
                provider = "remote-openai", endpoint = legit.url, model = "m1", apiKeyRef = "file:$keyFile",
            ))
            val port = node.appApiPort
            try {
                // control: the configured endpoint does get the configured key
                assertEquals(200, call(port, "POST", "/api/v1/embedder/models", """{"provider":"remote-openai"}""").statusCode())
                assertTrue("Bearer STOREDKEY" in legit.auth, legit.auth.toString())
                // naming the configured endpoint explicitly still uses the configured key
                legit.auth.clear()
                call(port, "POST", "/api/v1/embedder/models", """{"provider":"remote-openai","endpoint":"${legit.url}"}""")
                assertTrue("Bearer STOREDKEY" in legit.auth, legit.auth.toString())

                // another endpoint: /models, /test and PUT send no key at all
                call(port, "POST", "/api/v1/embedder/models", """{"provider":"remote-openai","endpoint":"${evil.url}"}""")
                call(port, "POST", "/api/v1/embedder/test", """{"provider":"remote-openai","endpoint":"${evil.url}","model":"m1"}""")
                val put = call(port, "PUT", "/api/v1/embedder", """{"provider":"remote-openai","endpoint":"${evil.url}","model":"m1"}""")
                assertEquals(200, put.statusCode(), put.body())
                call(port, "POST", "/api/v1/embedder/models", """{"provider":"remote-openai"}""") // now the configured one
                assertTrue(evil.auth.isNotEmpty(), "the other endpoint was contacted")
                assertTrue(evil.auth.none { "STOREDKEY" in it }, "the stored key leaked: ${evil.auth}")
                val saved = dir.resolve("config.json").takeIf { Files.exists(it) }?.let { Files.readString(it) }
                if (saved != null) assertFalse("stored.key" in saved, "the persisted config keeps no key ref for the new endpoint: $saved")
            } finally {
                node.shutdown()
            }
        } }
    }

    @Test
    fun `on a hosted engine an embedder key must be an embedder- secret from POST secrets`() {
        val dir = Files.createTempDirectory("svod-embkey-deny-")
        val node = start(dir, deny = true)
        val port = node.appApiPort
        try {
            fun secret(name: String, value: String): String {
                val r = call(port, "POST", "/api/v1/secrets", """{"name":"$name","value":"$value"}""")
                assertEquals(201, r.statusCode(), r.body())
                return Regex("\"ref\":\"([^\"]+)\"").find(r.body())!!.groupValues[1]
            }
            val good = secret("embedder-openai", "sk-GOODKEY")
            val git = secret("git-remote-main", "https://u:GITTOKEN@github.com/org/r.git")
            val agent = secret("agent-x", "svk_AGENTTOKEN")
            val secretsDir = Path.of(good.removePrefix("file:")).parent
            val outside = dir.resolve("outside.txt").also { Files.writeString(it, "OUTSIDE") }
            val linkOut = secretsDir.resolve("embedder-out.secret").also { Files.createSymbolicLink(it, outside) }
            val linkGit = secretsDir.resolve("embedder-git.secret").also { Files.createSymbolicLink(it, Path.of(git.removePrefix("file:"))) }
            Files.writeString(secretsDir.resolve("user-x.key"), "USERKEY")

            val refused = listOf(
                "env:HOME", "env:PATH", "keychain:svod", "file:/proc/self/environ", "file:/etc/hostname", "file:$outside",
                git, agent, "file:$linkOut", "file:$linkGit", "file:${secretsDir.resolve("user-x.key")}",
                "file:$secretsDir/../secrets/embedder-openai.secret", "file:${secretsDir.resolve("embedder-missing.secret")}",
            )
            for (ref in refused) for ((method, path) in listOf("PUT" to "/api/v1/embedder", "POST" to "/api/v1/embedder/models")) {
                val r = call(port, method, path, """{"provider":"none","apiKeyRef":"$ref"}""")
                assertEquals(422, r.statusCode(), "$method $path $ref: ${r.body()}")
                for (leak in listOf("GOODKEY", "GITTOKEN", "AGENTTOKEN", "USERKEY", "OUTSIDE", "PATH=")) assertFalse(leak in r.body(), "$ref leaked $leak: ${r.body()}")
            }
            val probe = call(port, "POST", "/api/v1/embedder/test", """{"provider":"none","apiKeyRef":"env:HOME"}""")
            assertTrue("\"ok\":false" in probe.body() && "embedder-" in probe.body(), probe.body())

            assertEquals(200, call(port, "POST", "/api/v1/embedder/models", """{"provider":"none","apiKeyRef":"$good"}""").statusCode())
            val put = call(port, "PUT", "/api/v1/embedder", """{"provider":"none","apiKeyRef":"$good"}""")
            assertEquals(200, put.statusCode(), put.body())
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `on a hosted engine a backup remote given as a ref must be a git-remote- secret`() {
        val dir = Files.createTempDirectory("svod-remote-deny-")
        val node = start(dir, deny = true)
        val port = node.appApiPort
        try {
            fun secret(name: String, value: String) =
                Regex("\"ref\":\"([^\"]+)\"").find(call(port, "POST", "/api/v1/secrets", """{"name":"$name","value":"$value"}""").body())!!.groupValues[1]
            val emb = secret("embedder-openai", "sk-GOODKEY")
            val git = secret("git-remote-main", "https://u:GITTOKEN@github.com/org/r.git")
            for (ref in listOf(emb, "env:HOME", "keychain:x", "file:/etc/hostname")) {
                val r = call(port, "PUT", "/api/v1/settings/backup", """{"remote":"$ref","enabled":true,"syncEnabled":true}""")
                assertEquals(422, r.statusCode(), "$ref: ${r.body()}")
                assertFalse("GOODKEY" in r.body() || "GITTOKEN" in r.body(), r.body())
            }
            val ok = call(port, "PUT", "/api/v1/settings/backup", """{"remote":"$git","enabled":true,"syncEnabled":true}""")
            assertEquals(200, ok.statusCode(), ok.body())
            assertFalse("GITTOKEN" in ok.body(), ok.body())
        } finally {
            node.shutdown()
        }
    }
}
