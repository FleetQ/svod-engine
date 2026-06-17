package dev.svod.engine.mcp

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.MarkdownChunker
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.index.SearchFilters
import dev.svod.engine.index.SearchQuery
import dev.svod.engine.security.SecretScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Path-A enumeration (context_pack enumerate) and the `remember` promotion gate. */
class MemoryToolsTest {

    private fun str(r: dev.svod.engine.mcp.ToolResult, k: String) = r.data[k]?.jsonPrimitive?.content

    @Test
    fun `context_pack enumerate returns every matching note in full, unranked, lifecycle-filtered`() = runBlocking {
        McpFixture().use { fx ->
            fx.engine.write("memory/policy/a.md", "---\ntype: policy\nstatus: active\n---\nAlways write JSON.", null, fx.write.author)
            fx.engine.write("memory/policy/b.md", "---\ntype: policy\nstatus: active\n---\nRefunds over 500 need approval.", null, fx.write.author)
            fx.engine.write("memory/policy/c.md", "---\ntype: policy\nstatus: revoked\n---\nObsolete policy.", null, fx.write.author)
            fx.index.waitIdle()

            // tiny budget must NOT cap enumerate — all active policies come back in full
            val r = fx.tools.contextPack(fx.write, SearchQuery("", SearchFilters(type = "policy")), tokenBudget = 1, enumerate = true)
            assertEquals("enumerate", str(r, "mode"))
            val blocks = r.data["blocks"]!!.jsonArray
            val paths = blocks.map { it.jsonObject["path"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("memory/policy/a.md", "memory/policy/b.md"), paths, "active policies only, revoked excluded")
            assertTrue(blocks.all { it.jsonObject["content"]!!.jsonPrimitive.content.isNotBlank() }, "full content present")
            assertEquals(paths.sorted(), blocks.map { it.jsonObject["path"]!!.jsonPrimitive.content }, "deterministic by path")
        }
    }

    @Test
    fun `remember gates writes - status by type, dedup, supersession`() = runBlocking {
        McpFixture().use { fx ->
            // fact → provisional, deterministic path
            val r1 = fx.tools.remember(fx.write, "Prod DB is in us-east-1.", "fact", "infra", 0.9, "run-1", null, null, null)
            assertEquals("written", str(r1, "status")); assertEquals("provisional", str(r1, "memoryStatus"))
            val factPath = str(r1, "path")!!
            assertEquals("provisional", MarkdownChunker.parse(fx.engine.read(factPath)!!.text).status)

            // preference → active
            val r2 = fx.tools.remember(fx.write, "User prefers terse answers.", "preference", null, null, null, null, null, null)
            assertEquals("active", str(r2, "memoryStatus"))

            // dedup: identical content+type ⇒ no second note
            val r3 = fx.tools.remember(fx.write, "Prod DB is in us-east-1.", "fact", null, null, null, null, null, null)
            assertEquals("deduped", str(r3, "status")); assertEquals(factPath, str(r3, "path"))

            // supersession: a new fact revokes + links the old one
            val r4 = fx.tools.remember(fx.write, "Prod DB moved to eu-west-1.", "fact", null, null, null, null, null, supersedes = factPath)
            assertEquals("written", str(r4, "status")); assertEquals(factPath, str(r4, "superseded"))
            val old = MarkdownChunker.parse(fx.engine.read(factPath)!!.text)
            assertEquals("revoked", old.status); assertEquals(str(r4, "path"), old.supersededBy)
        }
    }

    @Test
    fun `remember is denied for a read-only agent`() = runBlocking {
        McpFixture().use { fx ->
            val r = fx.tools.remember(fx.read, "should not persist", "fact", null, null, null, null, null, null)
            assertTrue(r.isError, "read-only agent must be denied")
            assertEquals(emptyList(), fx.engine.list().filter { it.startsWith("memory/") })
        }
    }

    @Test
    fun `remember secret-scans content (blocked, not written)`() = runBlocking {
        val root = Files.createTempDirectory("svod-remember-secret-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SvodEngine.open(root, scope, SecretScanner(enabled = true))
        val index = IndexService(root, root.resolve(".svod").resolve("index"), NoneEmbedder).start()
        try {
            val tools = SvodTools(engine, index, AuditLog(root.resolve(".svod/audit/a.log")), RateLimiter.default())
            val secret = "-----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY-----"
            val r = tools.remember(WRITE_IDENTITY(), secret, "fact", null, null, null, null, null, null)
            assertTrue(r.isError, "secret content must be blocked")
            assertEquals(emptyList(), engine.list().filter { it.startsWith("memory/") }, "no memory note written")
        } finally { index.close(); engine.close() }
    }

    private fun WRITE_IDENTITY(): AgentIdentity = AgentRegistry(listOf(WRITE_AGENT)).byAgentId("scribe")!!
}
