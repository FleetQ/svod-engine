package dev.svod.engine.mcp

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.index.Embedder
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.MarkdownChunker
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.memory.Classification
import dev.svod.engine.memory.MemoryAdjudicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `remember` fact-classification layer: one test per branch (NEW / DUPLICATE / UPDATE /
 * CONTRADICTION / UNCERTAIN), plus an integration test that drives CONTRADICTION through the
 * single-writer actor under concurrent writes.
 */
class MemoryClassificationTest {

    // ---- fixtures ----

    /** Like [McpFixture] but with a pluggable adjudicator + embedder, which is what classification turns on. */
    private class ClassifyFixture(
        adjudicator: MemoryAdjudicator? = null,
        embedder: Embedder = NoneEmbedder,
    ) : AutoCloseable {
        val root: Path = Files.createTempDirectory("svod-classify-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine: SvodEngine = SvodEngine.open(root, scope)
        val index: IndexService = IndexService(root, root.resolve(".svod").resolve("index"), embedder).start()
        val registry = AgentRegistry(listOf(WRITE_AGENT))
        val tools = SvodTools(
            engine, index,
            AuditLog(root.resolve(".svod").resolve("audit").resolve("audit.log")),
            RateLimiter.default(), EventBus(), adjudicator,
        )

        init { engine.onCommit { index.onCommit(it) } }

        val write: AgentIdentity get() = registry.byAgentId("scribe")!!

        override fun close() { index.close(); engine.close() }
    }

    /** Always returns [verdict] — stands in for a configured LLM without needing one. */
    private class FixedAdjudicator(private val verdict: Classification) : MemoryAdjudicator {
        var calls = 0
        override fun adjudicate(incoming: String, candidate: String, type: String): MemoryAdjudicator.Verdict {
            calls++
            return MemoryAdjudicator.Verdict(verdict, 0.88, "stub adjudicator said ${verdict.name}")
        }
    }

    /** Deterministic embedder with hand-scripted vectors, so a cosine band can be targeted exactly. */
    private class ScriptedEmbedder(private val vectors: Map<String, FloatArray>) : Embedder {
        override val model = "scripted-test"
        override val dim = 3
        override fun embedPassages(texts: List<String>): List<FloatArray> =
            texts.map { vectors[it.trim()] ?: floatArrayOf(0f, 0f, 1f) }
        override fun embedQuery(text: String): FloatArray = embedPassages(listOf(text)).first()
    }

    private fun str(r: ToolResult, k: String) = r.data[k]?.jsonPrimitive?.content
    private fun fm(fx: ClassifyFixture, path: String) = runBlocking {
        MarkdownChunker.parse(fx.engine.read(path)!!.text)
    }

    private val US = "Prod DB is in us-east-1."
    private val EU = "Prod DB is in eu-west-1."

    // ---- one test per classification branch ----

    @Test
    fun `NEW - nothing comparable stored yet`() = runBlocking {
        ClassifyFixture().use { fx ->
            val r = fx.tools.remember(fx.write, US, "fact", "infra", null, null, null, null, null)
            assertEquals("written", str(r, "status"))
            assertEquals("NEW", str(r, "classification"))
            assertNull(r.data["relatedNote"], "nothing to relate to")
            assertEquals(false, fm(fx, str(r, "path")!!).frontmatter.containsKey("needs-review"))
        }
    }

    @Test
    fun `NEW - an unrelated fact about the same type is not forced into a relation`() = runBlocking {
        ClassifyFixture().use { fx ->
            fx.tools.remember(fx.write, US, "fact", "infra", null, null, null, null, null)
            fx.index.waitIdle()

            val r = fx.tools.remember(fx.write, "The CI runner has 8 vCPU.", "fact", "infra", null, null, null, null, null)
            assertEquals("written", str(r, "status"))
            assertEquals("NEW", str(r, "classification"), "no token/vector overlap ⇒ unrelated")
        }
    }

    @Test
    fun `DUPLICATE - exact restatement is a no-op that points at the original`() = runBlocking {
        ClassifyFixture().use { fx ->
            val first = fx.tools.remember(fx.write, US, "fact", "infra", null, null, null, null, null)
            fx.index.waitIdle()

            // Same text, different spacing/case/punctuation — still the same statement.
            val again = fx.tools.remember(fx.write, "  prod db is in us-east-1  ", "fact", "infra", null, null, null, null, null)
            assertEquals("deduped", str(again, "status"))
            assertEquals("DUPLICATE", str(again, "classification"))
            assertEquals(str(first, "path"), str(again, "relatedNote"))
            assertEquals(1, fx.engine.list().count { it.startsWith("memory/fact/") }, "no second note")
        }
    }

    @Test
    fun `DUPLICATE - near-duplicate detected by embedding cosine, not by wording`() = runBlocking {
        val a = "Prod DB lives in us-east-1."
        val b = "The production database is hosted in us-east-1."
        // Deliberately low lexical overlap, deliberately high cosine: only the vector path can catch this.
        val embedder = ScriptedEmbedder(
            mapOf(a to floatArrayOf(1f, 0f, 0f), b to floatArrayOf(0.99f, 0.141f, 0f)),
        )
        ClassifyFixture(embedder = embedder).use { fx ->
            val first = fx.tools.remember(fx.write, a, "fact", "infra", null, null, null, null, null)
            fx.index.waitIdle()

            val second = fx.tools.remember(fx.write, b, "fact", "infra", null, null, null, null, null)
            assertEquals("deduped", str(second, "status"), "cosine ≥ 0.97 ⇒ duplicate")
            assertEquals("DUPLICATE", str(second, "classification"))
            assertEquals(str(first, "path"), str(second, "relatedNote"))
        }
    }

    @Test
    fun `UNCERTAIN - ambiguous band with no LLM configured persists flagged for review`() = runBlocking {
        ClassifyFixture(adjudicator = null).use { fx ->
            fx.tools.remember(fx.write, US, "fact", "infra", null, null, null, null, null)
            fx.index.waitIdle()

            val r = fx.tools.remember(fx.write, EU, "fact", "infra", null, null, null, null, null)
            assertEquals("written", str(r, "status"), "still persisted — never dropped")
            assertEquals("UNCERTAIN", str(r, "classification"))
            assertEquals(true, r.data["needsReview"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals(true, fm(fx, str(r, "path")!!).frontmatter["needs-review"])
            assertEquals(2, fx.engine.list().count { it.startsWith("memory/fact/") }, "both memories kept")
        }
    }

    @Test
    fun `CONTRADICTION - both memories persist, linked, neither overwritten`() = runBlocking {
        val judge = FixedAdjudicator(Classification.CONTRADICTION)
        ClassifyFixture(adjudicator = judge).use { fx ->
            val first = fx.tools.remember(fx.write, US, "fact", "infra", null, null, null, null, null)
            fx.index.waitIdle()
            val firstPath = str(first, "path")!!
            val firstRevision = fx.engine.read(firstPath)!!.revision

            val second = fx.tools.remember(fx.write, EU, "fact", "infra", null, null, null, null, null)
            val secondPath = str(second, "path")!!

            assertEquals("written", str(second, "status"))
            assertEquals("CONTRADICTION", str(second, "classification"))
            assertEquals(firstPath, str(second, "contradicts"), "surfaced in the tool response")
            assertEquals(firstPath, str(second, "relatedNote"))
            assertTrue(judge.calls > 0, "the ambiguous band consulted the adjudicator")

            // The new note carries the link...
            assertEquals(firstPath, fm(fx, secondPath).frontmatter["contradicts"])
            assertTrue(fm(fx, secondPath).body.contains("eu-west-1"))
            // ...and the older memory is byte-for-byte untouched: not revoked, not rewritten.
            assertEquals(firstRevision, fx.engine.read(firstPath)!!.revision, "predecessor must not be overwritten")
            assertEquals("provisional", fm(fx, firstPath).status)
            assertNull(fm(fx, firstPath).supersededBy)
        }
    }

    @Test
    fun `UPDATE - predecessor is revoked and linked, its history survives`() = runBlocking {
        ClassifyFixture(adjudicator = FixedAdjudicator(Classification.UPDATE)).use { fx ->
            val first = fx.tools.remember(fx.write, US, "fact", "infra", null, null, null, null, null)
            fx.index.waitIdle()
            val firstPath = str(first, "path")!!

            val second = fx.tools.remember(fx.write, EU, "fact", "infra", null, null, null, null, null)
            val secondPath = str(second, "path")!!

            assertEquals("written", str(second, "status"))
            assertEquals("UPDATE", str(second, "classification"))
            assertEquals(firstPath, str(second, "superseded"))

            // Predecessor revoked + linked forward; successor links back.
            assertEquals("revoked", fm(fx, firstPath).status)
            assertEquals(secondPath, fm(fx, firstPath).supersededBy)
            assertEquals(firstPath, fm(fx, secondPath).frontmatter["supersedes"])

            // jgit keeps the predecessor's earlier content reachable — an update is not a deletion.
            val history = fx.engine.history(firstPath, 10)
            assertTrue(history.size >= 2, "predecessor has both its original and its revocation commit")
            val original = fx.engine.getRevision(firstPath, history.last().commit)
            assertTrue(original!!.text.contains("us-east-1"), "original content recoverable from history")
        }
    }

    @Test
    fun `UPDATE - a caller-declared supersedes is honored without inference`() = runBlocking {
        // No adjudicator and an inference-hostile pair: the caller's own declaration must still win.
        ClassifyFixture(adjudicator = null).use { fx ->
            val first = fx.tools.remember(fx.write, US, "fact", "infra", null, null, null, null, null)
            fx.index.waitIdle()
            val firstPath = str(first, "path")!!

            val second = fx.tools.remember(fx.write, "Unrelated wording entirely.", "fact", "infra", null, null, null, null, supersedes = firstPath)
            assertEquals("written", str(second, "status"))
            assertEquals("UPDATE", str(second, "classification"))
            assertEquals(firstPath, str(second, "superseded"))
            assertEquals("revoked", fm(fx, firstPath).status)
        }
    }

    @Test
    fun `a missing supersedes target is reported, not silently ignored`() = runBlocking {
        ClassifyFixture().use { fx ->
            val r = fx.tools.remember(fx.write, US, "fact", "infra", null, null, null, null, supersedes = "memory/fact/nope.md")
            assertEquals("not_found", r.status)
            assertEquals(emptyList(), fx.engine.list().filter { it.startsWith("memory/fact/") })
        }
    }

    @Test
    fun `classification never compares across subjects`() = runBlocking {
        ClassifyFixture(adjudicator = FixedAdjudicator(Classification.CONTRADICTION)).use { fx ->
            fx.tools.remember(fx.write, US, "fact", "infra", null, null, null, null, null)
            fx.index.waitIdle()

            // Same wording, different subject entity ⇒ not a candidate at all.
            val other = fx.tools.remember(fx.write, EU, "fact", "billing", null, null, null, null, null)
            assertEquals("NEW", str(other, "classification"))
            assertNull(other.data["relatedNote"])
        }
    }

    @Test
    fun `secret content is refused before classification can embed it`() = runBlocking {
        // A remote embedder/LLM would otherwise see the secret before the write-time scan refused it.
        val root = Files.createTempDirectory("svod-classify-secret-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SvodEngine.open(root, scope, dev.svod.engine.security.SecretScanner(enabled = true))
        val index = IndexService(root, root.resolve(".svod").resolve("index"), NoneEmbedder).start()
        try {
            var embedded = false
            val judge = object : MemoryAdjudicator {
                override fun adjudicate(incoming: String, candidate: String, type: String): MemoryAdjudicator.Verdict? {
                    embedded = true
                    return null
                }
            }
            val tools = SvodTools(
                engine, index, AuditLog(root.resolve(".svod/audit/a.log")),
                RateLimiter.default(), EventBus(), judge,
            )
            val agent = AgentRegistry(listOf(WRITE_AGENT)).byAgentId("scribe")!!
            val secret = "-----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY-----"
            val r = tools.remember(agent, secret, "fact", "infra", null, null, null, null, null)

            assertTrue(r.isError, "secret content must be blocked")
            assertEquals("blocked", r.status)
            assertTrue(!embedded, "the adjudicator must never see secret content")
            assertEquals(emptyList(), engine.list().filter { it.startsWith("memory/") })
        } finally { index.close(); engine.close() }
    }

    // ---- integration: through the actor, under concurrency ----

    @Test
    fun `concurrent identical remembers collapse to exactly one note`() = runBlocking {
        ClassifyFixture().use { fx ->
            // Same content ⇒ same deterministic path ⇒ the writers race on ONE path. Only the guard
            // inside the actor can stop the losers from clobbering the winner: each is refused as
            // stale, re-plans against live state, and sees the note that landed.
            val results = (1..8).map {
                async { fx.tools.remember(fx.write, US, "fact", "infra", null, null, null, null, null) }
            }.awaitAll()

            assertEquals(1, fx.engine.list().count { it.startsWith("memory/fact/") }, "exactly one note survives")
            assertEquals(1, results.count { str(it, "status") == "written" }, "exactly one writer won")
            assertEquals(7, results.count { str(it, "status") == "deduped" }, "every loser re-planned into a dedup")
            assertTrue(results.map { str(it, "path") }.toSet().size == 1, "all callers agree on the path")
        }
    }

    @Test
    // ": Unit" is load-bearing — the block ends in a value-returning assertion, and a @Test method
    // that compiles to a non-void return is silently not collected by JUnit.
    fun `CONTRADICTION under concurrent writes keeps every memory and overwrites none`(): Unit = runBlocking {
        ClassifyFixture(adjudicator = FixedAdjudicator(Classification.CONTRADICTION)).use { fx ->
            val seed = fx.tools.remember(fx.write, US, "fact", "infra", null, null, null, null, null)
            fx.index.waitIdle()
            val seedPath = str(seed, "path")!!
            val seedRevision = fx.engine.read(seedPath)!!.revision

            // Contradictory facts about the SAME subject, all in flight at once. Each plans off the
            // actor and applies inside it; whoever's evidence moved gets replanned, never clobbered.
            val regions = listOf("eu-west-1", "ap-south-1", "sa-east-1", "af-south-1")
            val results = regions.map { region ->
                async { fx.tools.remember(fx.write, "Prod DB is in $region.", "fact", "infra", null, null, null, null, null) }
            }.awaitAll()

            // Every call ended in a defined state — written, or refused as a conflict. Never a silent loss.
            assertTrue(
                results.all { str(it, "status") == "written" || it.status == "conflict" },
                "unexpected statuses: ${results.map { it.status }}",
            )

            val written = results.filter { str(it, "status") == "written" }
            val notes = fx.engine.list().filter { it.startsWith("memory/fact/") }
            assertEquals(1 + written.size, notes.size, "exactly the seed plus each successful write")

            // Each successful write is intact and distinct — no write landed on another's path.
            assertEquals(written.size, written.map { str(it, "path") }.toSet().size, "distinct paths")
            for (r in written) {
                val path = str(r, "path")!!
                val body = fm(fx, path).body
                val region = regions.first { body.contains(it) }
                assertTrue(body.contains("Prod DB is in $region."), "content preserved verbatim at $path")
            }

            // The seed was never overwritten, never revoked — CONTRADICTION keeps both sides.
            assertEquals(seedRevision, fx.engine.read(seedPath)!!.revision, "seed memory untouched")
            assertEquals("provisional", fm(fx, seedPath).status)
            assertNull(fm(fx, seedPath).supersededBy)

            // At least one contradiction link was recorded against the seed.
            val linked = written.mapNotNull { str(it, "contradicts") }
            assertTrue(linked.isNotEmpty(), "concurrent contradictions must still be linked")
            assertNotNull(fm(fx, str(written.first(), "path")!!).frontmatter["contradicts"])
        }
    }
}
