package dev.svod.engine.api

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.mcp.AuditLog
import dev.svod.engine.mcp.McpFixture
import dev.svod.engine.mcp.RateLimiter
import dev.svod.engine.mcp.SvodTools
import dev.svod.engine.security.SecretScanner
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
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `GET /api/v1/audit`: engine-admin only, read-only view of a vault's agent audit trail. */
class AuditEndpointTest {

    private class Fx : AutoCloseable {
        val mcp = McpFixture(vaultId = "main")
        private val secrets = Files.createTempDirectory("svod-audit-secrets-")
        private fun keyRef(id: String, value: String) = secrets.resolve("user-$id.key").also { Files.writeString(it, value) }.let { "file:$it" }
        val config = SvodConfig(
            vaults = listOf(SvodConfig.VaultSettings("main", mcp.root.toString())),
            defaultVault = "main",
            users = listOf(
                SvodConfig.UserSettings("boss", "Boss Person", "boss@co", keyRef("boss", "k-admin"), admin = true),
                SvodConfig.UserSettings("maria", "Maria", "maria@co", keyRef("maria", "k-editor"), grants = listOf(SvodConfig.VaultGrant("main", "EDITOR"))),
                SvodConfig.UserSettings("rita", "Rita", "rita@co", keyRef("rita", "k-reader"), grants = listOf(SvodConfig.VaultGrant("main", "READER"))),
            ),
        )
        private val view = object : VaultView {
            override val id = "main"
            override val name = "main"
            override val engine = mcp.engine
            override val index = mcp.index
            override val conflicts = null
            override fun syncStatus(): SyncStatusDto? = null
        }
        private val router = object : VaultRouter {
            override fun ids() = listOf("main")
            override fun defaultId() = "main"
            override fun resolve(id: String?): VaultView? = if (id == null || id == "main") view else null
            override fun all() = listOf(view)
        }
        private val running = AppApiServer(
            vaults = router, eventBus = EventBus(),
            config = AppApiServer.Config(localAdmin = false),
            users = UserRegistry(config.toUserSpecs()),
        ).start(0)
        private val http = HttpClient.newHttpClient()
        fun get(query: String, key: String? = "k-admin"): HttpResponse<String> {
            val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${running.port}/api/v1/audit$query"))
            if (key != null) b.header("Authorization", "Bearer $key")
            return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString())
        }
        val auditFile get() = AuditLog.fileFor(mcp.root)

        /** An ok write, a conflict, and a remember blocked by the secret scanner — all through the MCP tools. */
        suspend fun produceTrail() {
            assertEquals("ok", mcp.tools.write(mcp.write, "c.md", "v1", expectedRevision = null).status)
            assertEquals("conflict", mcp.tools.write(mcp.write, "c.md", "v2", expectedRevision = null).status)
            // The fixture's engine runs without a scanner; a scanning engine with the SAME audit log
            // records the blocked remember into this vault's trail.
            val scanRoot = Files.createTempDirectory("svod-audit-scan-")
            val scanning = SvodEngine.open(scanRoot, CoroutineScope(SupervisorJob() + Dispatchers.Default), SecretScanner(enabled = true))
            val idx = IndexService(scanRoot, scanRoot.resolve(".svod").resolve("index"), NoneEmbedder).start()
            try {
                val tools = SvodTools(scanning, idx, mcp.audit, RateLimiter.default())
                val r = tools.remember(mcp.write, SECRET, "fact", null, null, null, null, null, null)
                assertTrue(r.isError, "secret content is blocked")
            } finally { idx.close(); scanning.close() }
        }

        override fun close() { running.stop(); mcp.close() }
    }

    private fun obj(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject
    private fun outcomes(body: String) = obj(body)["entries"]!!.jsonArray.map { it.jsonObject["outcome"]!!.jsonPrimitive.content }

    @Test
    fun `admin reads the trail newest first, with rule names and never the secret`(): Unit = runBlocking {
        Fx().use { fx ->
            fx.produceTrail()
            val res = fx.get("?vault=main")
            assertEquals(200, res.statusCode(), res.body())
            val j = obj(res.body())
            val entries = j["entries"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("blocked", "conflict", "ok"), entries.map { it["outcome"]!!.jsonPrimitive.content })
            assertEquals(listOf(3L, 2L, 1L), entries.map { it["seq"]!!.jsonPrimitive.long })
            assertEquals(3, j["total"]!!.jsonPrimitive.int)
            assertEquals(0, j["skipped"]!!.jsonPrimitive.int)
            assertFalse("nextBefore" in j.keys, "null nextBefore is omitted")
            val ok = entries.last()
            assertEquals("scribe", ok["agentId"]!!.jsonPrimitive.content)
            assertEquals("write", ok["tool"]!!.jsonPrimitive.content)
            assertEquals("c.md", ok["path"]!!.jsonPrimitive.content)
            val tsMs = ok["tsMs"]!!.jsonPrimitive.long
            assertEquals(tsMs, java.time.Instant.parse(ok["ts"]!!.jsonPrimitive.content).toEpochMilli())
            val blocked = entries.first()
            assertEquals("remember", blocked["tool"]!!.jsonPrimitive.content)
            assertTrue(blocked["detail"]!!.jsonPrimitive.content.isNotBlank(), "carries the rule name")
            assertFalse("MIIabc" in res.body(), "the matched secret value never appears: ${res.body()}")
            assertFalse("PRIVATE KEY-----" in res.body())
            // Omitting ?vault= uses the default vault.
            assertEquals(3, obj(fx.get("").body())["total"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `non-admins get 403, no key 401, an unknown vault 404`(): Unit = runBlocking {
        Fx().use { fx ->
            fx.produceTrail()
            assertEquals(403, fx.get("?vault=main", "k-editor").statusCode())
            assertEquals(403, fx.get("?vault=main", "k-reader").statusCode())
            assertEquals(401, fx.get("?vault=main", null).statusCode())
            assertEquals(401, fx.get("?vault=main", "nope").statusCode())
            val unknown = fx.get("?vault=ghost")
            assertEquals(404, unknown.statusCode())
            assertEquals("not_found", obj(unknown.body())["error"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `filters by agent, outcome and since`(): Unit = runBlocking {
        Fx().use { fx ->
            fx.produceTrail()
            assertEquals(listOf("blocked", "conflict"), outcomes(fx.get("?vault=main&outcome=blocked,conflict").body()))
            assertEquals(listOf("ok"), outcomes(fx.get("?vault=main&outcome=ok").body()))
            assertEquals(3, outcomes(fx.get("?vault=main&agent=scribe").body()).size)
            assertEquals(0, obj(fx.get("?vault=main&agent=nobody").body())["total"]!!.jsonPrimitive.int)
            val future = System.currentTimeMillis() + 3_600_000
            assertEquals(0, outcomes(fx.get("?vault=main&since=$future").body()).size)
            assertEquals(3, outcomes(fx.get("?vault=main&since=0").body()).size)
            assertEquals(3, outcomes(fx.get("?vault=main&since=2020-01-01T00:00:00Z").body()).size)
            assertEquals(0, outcomes(fx.get("?vault=main&since=${java.time.Instant.ofEpochMilli(future)}").body()).size)
        }
    }

    @Test
    fun `bad parameters are 400`(): Unit = runBlocking {
        Fx().use { fx ->
            for (q in listOf("since=yesterday", "limit=0", "limit=501", "limit=x", "before=abc")) {
                val r = fx.get("?vault=main&$q")
                assertEquals(400, r.statusCode(), "$q: ${r.body()}")
            }
            assertEquals(200, fx.get("?vault=main&limit=500").statusCode())
            assertEquals(200, fx.get("?vault=main&limit=1").statusCode())
        }
    }

    @Test
    fun `pages with a stable before cursor`(): Unit = runBlocking {
        Fx().use { fx ->
            fx.produceTrail()
            val p1 = obj(fx.get("?vault=main&limit=2").body())
            assertEquals(listOf(3L, 2L), p1["entries"]!!.jsonArray.map { it.jsonObject["seq"]!!.jsonPrimitive.long })
            assertEquals(3, p1["total"]!!.jsonPrimitive.int)
            val next = p1["nextBefore"]!!.jsonPrimitive.long
            assertEquals(2L, next)
            fx.mcp.tools.write(fx.mcp.write, "d.md", "new", expectedRevision = null)   // a newer line lands
            val p2 = obj(fx.get("?vault=main&limit=2&before=$next").body())
            assertEquals(listOf(1L), p2["entries"]!!.jsonArray.map { it.jsonObject["seq"]!!.jsonPrimitive.long })
            assertFalse("nextBefore" in p2.keys)
        }
    }

    @Test
    fun `a malformed line is skipped and counted, not a 500`(): Unit = runBlocking {
        Fx().use { fx ->
            fx.produceTrail()
            Files.writeString(fx.auditFile, "garbage\n{\"ts\":1,\"agentId\":", StandardOpenOption.APPEND)
            val res = fx.get("?vault=main")
            assertEquals(200, res.statusCode(), res.body())
            val j = obj(res.body())
            assertEquals(2, j["skipped"]!!.jsonPrimitive.int)
            assertEquals(3, j["total"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `a line torn inside a multi-byte character is skipped and counted, not a 500`(): Unit = runBlocking {
        Fx().use { fx ->
            fx.produceTrail()
            val torn = """{"ts":1,"agentId":"scribe","tool":"write","outcome":"ok","path":"бел""".toByteArray(Charsets.UTF_8)
            Files.write(fx.auditFile, torn.copyOfRange(0, torn.size - 1), StandardOpenOption.APPEND)   // cut mid-letter
            val res = fx.get("?vault=main")
            assertEquals(200, res.statusCode(), res.body())
            val j = obj(res.body())
            assertEquals(1, j["skipped"]!!.jsonPrimitive.int)
            assertEquals(3, j["total"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `reading never creates or changes the trail`(): Unit = runBlocking {
        Fx().use { fx ->
            Files.deleteIfExists(fx.auditFile)
            val res = fx.get("?vault=main")
            assertEquals(200, res.statusCode(), res.body())
            assertEquals(0, obj(res.body())["total"]!!.jsonPrimitive.int)
            assertFalse(Files.exists(fx.auditFile), "GET must not create the log")

            fx.produceTrail()
            val bytes = Files.readAllBytes(fx.auditFile)
            fx.get("?vault=main")
            assertTrue(bytes.contentEquals(Files.readAllBytes(fx.auditFile)), "GET must not write the log")
        }
    }

    private companion object {
        const val SECRET = "-----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY-----"
    }
}
