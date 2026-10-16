package dev.svod.engine.mcp

import dev.svod.engine.api.ApiFixture
import dev.svod.engine.api.AppApiServer
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.MarkdownChunker
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.memory.Classification
import dev.svod.engine.memory.MemoryAdjudicator
import dev.svod.engine.memory.MemoryReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `memory.requireReview`: on (default) a remembered fact/policy waits for a person as `provisional`;
 * off it is stored `active`, except UNCERTAIN and CONTRADICTION, which still go to the review queue.
 */
class MemoryReviewSettingTest {

    private class Fixture(requireReview: Boolean, adjudicator: MemoryAdjudicator? = null) : AutoCloseable {
        val root: Path = Files.createTempDirectory("svod-review-setting-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine: SvodEngine = SvodEngine.open(root, scope)
        val index: IndexService = IndexService(root, root.resolve(".svod").resolve("index"), NoneEmbedder).start()
        val registry = AgentRegistry(listOf(WRITE_AGENT))
        val tools = SvodTools(
            engine, index, AuditLog(root.resolve(".svod").resolve("audit").resolve("audit.log")),
            RateLimiter.default(), EventBus(), adjudicator, requireReview = requireReview,
        )

        init { engine.onCommit { index.onCommit(it) } }

        val write: AgentIdentity get() = registry.byAgentId("scribe")!!

        fun remember(content: String, type: String = "fact"): ToolResult = runBlocking {
            tools.remember(write, content, type, "infra", null, null, null, null, null)
        }

        fun fileStatus(path: String): String? = runBlocking { MarkdownChunker.parse(engine.read(path)!!.text).status }

        fun queued(): Set<String> = runBlocking {
            index.waitIdle()
            MemoryReview.list(engine, index, MemoryReview.MAX_LIMIT).first.map { it.path }.toSet()
        }

        override fun close() { index.close(); engine.close() }
    }

    private class FixedAdjudicator(private val verdict: Classification) : MemoryAdjudicator {
        override fun adjudicate(incoming: String, candidate: String, type: String) =
            MemoryAdjudicator.Verdict(verdict, 0.88, "stub adjudicator said ${verdict.name}")
    }

    private fun str(r: ToolResult, k: String) = r.data[k]?.jsonPrimitive?.content

    private val US = "Prod DB is in us-east-1."
    private val EU = "Prod DB is in eu-west-1."

    @Test
    fun `default - a remembered fact and policy are provisional and queued`() {
        Fixture(requireReview = true).use { fx ->
            val fact = fx.remember(US)
            val policy = fx.remember("Deploys go through a PR.", "policy")
            for (r in listOf(fact, policy)) {
                assertEquals("provisional", str(r, "memoryStatus"))
                assertEquals("provisional", fx.fileStatus(str(r, "path")!!))
            }
            assertEquals(setOf(str(fact, "path"), str(policy, "path")), fx.queued())
        }
    }

    @Test
    fun `review off - a remembered fact and policy are active and not queued`() {
        Fixture(requireReview = false).use { fx ->
            val fact = fx.remember(US)
            val policy = fx.remember("Deploys go through a PR.", "policy")
            for (r in listOf(fact, policy)) {
                assertEquals("NEW", str(r, "classification"))
                assertEquals("active", str(r, "memoryStatus"))
                assertEquals("active", fx.fileStatus(str(r, "path")!!))
            }
            assertTrue(fx.queued().isEmpty())

            // DUPLICATE is unchanged: a no-op that reports the stored memory's status.
            val again = fx.remember(US)
            assertEquals("deduped", str(again, "status"))
            assertEquals("active", str(again, "memoryStatus"))
        }
    }

    @Test
    fun `review off - an explicit caller status still wins`() {
        Fixture(requireReview = false).use { fx ->
            val r = runBlocking { fx.tools.remember(fx.write, US, "fact", "infra", null, null, "provisional", null, null) }
            assertEquals("provisional", fx.fileStatus(str(r, "path")!!))
        }
    }

    @Test
    fun `review off - UNCERTAIN stays provisional and queued`() {
        Fixture(requireReview = false, adjudicator = null).use { fx ->
            val first = fx.remember(US)
            fx.index.waitIdle()
            val r = fx.remember(EU)
            assertEquals("UNCERTAIN", str(r, "classification"))
            assertEquals("provisional", str(r, "memoryStatus"))
            assertEquals("provisional", fx.fileStatus(str(r, "path")!!))
            assertEquals(setOf(str(r, "path")), fx.queued(), "only the uncertain one waits; ${str(first, "path")} is active")
        }
    }

    @Test
    fun `review off - CONTRADICTION stays provisional and queued`() {
        Fixture(requireReview = false, adjudicator = FixedAdjudicator(Classification.CONTRADICTION)).use { fx ->
            val first = fx.remember(US)
            fx.index.waitIdle()
            val r = fx.remember(EU)
            assertEquals("CONTRADICTION", str(r, "classification"))
            assertEquals(str(first, "path"), str(r, "contradicts"))
            assertEquals("provisional", str(r, "memoryStatus"))
            assertEquals("provisional", fx.fileStatus(str(r, "path")!!))
            assertEquals(setOf(str(r, "path")), fx.queued())
        }
    }

    @Test
    fun `MCP text tells agents what the setting does`() {
        val on = SvodMcpServer.instructions(requireReview = true)
        val off = SvodMcpServer.instructions(requireReview = false)
        assertEquals(SvodMcpServer.INSTRUCTIONS, on)
        assertTrue("Do not pass status='active' for a fact or policy" in on)
        assertFalse("until a person approves it in the Svod app. Do not pass" in off)
        assertTrue("A fact or policy is stored 'active'" in off)
        assertTrue("UNCERTAIN or CONTRADICTION" in off)
    }

    @Test
    fun `config defaults to review on and reads memory requireReview`() {
        assertTrue(SvodConfig().memory.requireReview)
        val dir = Files.createTempDirectory("svod-config-")
        val on = dir.resolve("on.json").also { Files.writeString(it, """{"vaultPath":"/tmp/v"}""") }
        val off = dir.resolve("off.json").also { Files.writeString(it, """{"vaultPath":"/tmp/v","memory":{"requireReview":false}}""") }
        assertTrue(SvodConfig.load(on).memory.requireReview)
        assertFalse(SvodConfig.load(off).memory.requireReview)
    }

    @Test
    fun `settings endpoint exposes memory requireReview`() {
        for (value in listOf(true, false)) {
            ApiFixture(apiConfig = AppApiServer.Config(memoryRequireReview = value)).use { fx ->
                val body = Json.parseToJsonElement(fx.get("/api/v1/settings").body()).jsonObject
                assertEquals(value, body["memory"]!!.jsonObject["requireReview"]!!.jsonPrimitive.boolean)
            }
        }
    }
}
