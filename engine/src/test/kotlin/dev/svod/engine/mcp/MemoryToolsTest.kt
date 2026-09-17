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

    @Test
    fun `supersession revokes a hand-written memory without rewriting its other frontmatter lines`() = runBlocking {
        McpFixture().use { fx ->
            val old = "memory/fact/handwritten.md"
            val raw = "---\ntype: fact\n# keep me\nstatus: active\ncreated: 2026-09-01T10:00:00Z\nsubject: \"база\"\n---\nDB in us-east-1.\n"
            fx.engine.write(old, raw, null, fx.write.author)
            val r = fx.tools.remember(fx.write, "DB moved to eu-west-1.", "fact", null, null, null, null, null, supersedes = old)
            assertEquals("written", str(r, "status"), r.data.toString())
            val text = fx.engine.read(old)!!.text
            assertEquals(
                "---\ntype: fact\n# keep me\nstatus: revoked\ncreated: 2026-09-01T10:00:00Z\nsubject: \"база\"\nsuperseded_by: '${str(r, "path")}'\n---\nDB in us-east-1.\n",
                text,
            )
        }
    }

    @Test
    fun `E1 remember stores a future expiresAt as an ISO instant and recall hides the memory after it`() = runBlocking {
        McpFixture().use { fx ->
            val dated = fx.tools.remember(fx.write, "Freeze window for the audit.", "preference", null, null, null, null, null, null, expiresAt = "2999-06-01")
            assertEquals("written", str(dated, "status"), dated.data.toString())
            assertEquals("2999-06-01T00:00:00Z", MarkdownChunker.parse(fx.engine.read(str(dated, "path")!!)!!.text).frontmatter["expires_at"])

            val soon = java.time.Instant.now().plusSeconds(3).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            val r = fx.tools.remember(fx.write, "Kestrel maintenance banner is up.", "preference", null, null, null, null, null, null, expiresAt = soon.toString())
            assertEquals("written", str(r, "status"), r.data.toString())
            val path = str(r, "path")!!
            val doc = MarkdownChunker.parse(fx.engine.read(path)!!.text)
            assertEquals(soon.toString(), doc.frontmatter["expires_at"])
            assertEquals(soon.epochSecond, doc.expiresAt)

            fx.index.waitIdle()
            fun hits() = fx.index.search(SearchQuery("kestrel", SearchFilters(), dev.svod.engine.index.SearchMode.KEYWORD, 10)).hits.map { it.path }
            assertTrue(path in hits(), "visible before it expires")
            while (java.time.Instant.now().epochSecond <= soon.epochSecond) Thread.sleep(100)
            assertTrue(path !in hits(), "hidden once expires_at has passed")
        }
    }

    @Test
    fun `E2 a past or unparseable expiresAt is a bad request and writes nothing`() = runBlocking {
        McpFixture().use { fx ->
            val head = fx.engine.head()
            for (bad in listOf("2001-01-01", "2001-01-01T00:00:00Z", "next tuesday")) {
                val r = fx.tools.remember(fx.write, "Should not persist $bad.", "fact", null, null, null, null, null, null, expiresAt = bad)
                assertTrue(r.isError, "expiresAt '$bad' must be refused")
                assertEquals("bad_request", str(r, "status"), r.data.toString())
            }
            assertEquals(emptyList(), fx.engine.list().filter { it.startsWith("memory/") }, "no memory note written")
            assertEquals(head, fx.engine.head(), "no commit")
        }
    }

    private fun WRITE_IDENTITY(): AgentIdentity = AgentRegistry(listOf(WRITE_AGENT)).byAgentId("scribe")!!
}
