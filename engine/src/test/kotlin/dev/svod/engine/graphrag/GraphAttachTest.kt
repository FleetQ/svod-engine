package dev.svod.engine.graphrag

import dev.svod.engine.core.GitCli
import dev.svod.engine.index.FakeEmbedder
import dev.svod.engine.index.INDEXER
import dev.svod.engine.index.IndexFixture
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.SearchQuery
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Incremental attachment: a note written after the last full build must become reachable through the
 * thematic map without a rebuild.
 *
 * The load-bearing assertions are again the negative ones — attachment must not call a model, must
 * not re-run community detection, must not touch the Lucene index or the vault, must not duplicate a
 * member when it runs twice, and must NOT file a note that has no close neighbour.
 *
 * NB (`mem:kotlin-junit-silent-skip`): every test body is a block body, so JUnit collects them.
 */
class GraphAttachTest {

    private class SpyLlm : SummaryLlm {
        override val provider = "spy"
        override val model = "spy-model"
        override val isActive = true
        val calls = AtomicInteger(0)
        override suspend fun summarise(prompt: String, system: String?): String? {
            calls.incrementAndGet()
            return "TITLE: Тема\nSUMMARY: Обобщение."
        }
    }

    /** Delegates to the real detector but counts invocations — attachment must add none. */
    private class CountingDetector : CommunityDetector {
        val calls = AtomicInteger(0)
        private val real = LouvainDetector()
        override fun detect(graph: NoteGraph): List<Partition> {
            calls.incrementAndGet()
            return real.detect(graph)
        }
    }

    private fun config(
        incremental: Boolean = true,
        simThreshold: Double = 0.5,
        simEdgesPerNote: Int = 4,
        attachThreshold: Double? = null,
    ) = GraphConfig(
        enabled = true,
        simEdgesPerNote = simEdgesPerNote,
        simThreshold = simThreshold,
        minCommunitySize = 2,
        summariseTopLevels = 1,
        rebuildOnStartup = false,
        incremental = incremental,
        attachThreshold = attachThreshold,
    )

    private fun awaitBuild(g: GraphService, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (g.status().state != GraphState.BUILDING.name) return
            Thread.sleep(25)
        }
        throw AssertionError("graph build did not finish within ${timeoutMs}ms")
    }

    private suspend fun IndexFixture.seedTwoTopics() {
        seed("cook/pasta.md", "# Паста\nrecipe tomato basil pasta boiling water\nsee [[cook/sauce.md]]")
        seed("cook/sauce.md", "# Сос\nrecipe tomato basil sauce simmer\nsee [[cook/pasta.md]]")
        seed("cook/salad.md", "# Салата\nrecipe tomato basil salad fresh")
        seed("infra/deploy.md", "# Deploy\nkubernetes cluster deployment rollout\nsee [[infra/nginx.md]]")
        seed("infra/nginx.md", "# Nginx\nkubernetes cluster nginx proxy rollout\nsee [[infra/deploy.md]]")
        seed("infra/tls.md", "# TLS\nkubernetes cluster tls certificate rollout")
    }

    /** The community at the coarsest level holding [path], or null when it is on no theme. */
    private fun holderOf(g: GraphService, path: String): Community? =
        g.communities(null, null, 500).firstOrNull { path in it.members }

    /**
     * Rewrites an EXISTING note and lets the index catch up.
     *
     * `IndexFixture.seed` always writes with `expectedRevision = null`, which on an existing path is
     * a `WriteOutcome.Conflict` — a returned value, not an exception, so a "seed over" silently does
     * nothing and any test built on it passes for the wrong reason. This reads the live revision
     * first and **asserts the write succeeded**.
     */
    private fun editNote(fx: IndexFixture, index: IndexService, path: String, content: String) {
        runBlocking {
            val rev = fx.engine.read(path)?.revision
            val outcome = fx.engine.write(path, content, expectedRevision = rev, author = INDEXER)
            assertTrue(
                outcome is dev.svod.engine.core.WriteOutcome.Success,
                "the edit did not land, so whatever follows would be testing nothing: $outcome",
            )
        }
        index.reconcileNow()
    }

    /** Writes a note that clearly belongs with the `cook/` cluster and lets the index catch up. */
    private fun addCookNote(fx: IndexFixture, index: IndexService, path: String = "cook/risotto.md") {
        runBlocking { fx.seed(path, "# Ризото\nrecipe tomato basil pasta rice simmer boiling water") }
        index.reconcileNow()
    }

    // ---- the point of the feature ----

    @Test
    fun `a note written after the build joins the theme its neighbours are in`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            assertNull(holderOf(g, "cook/risotto.md"), "the note does not exist yet")

            addCookNote(fx, index)
            // Before attachment the note is searchable but on no theme — the exact gap this closes.
            assertTrue(
                index.search(SearchQuery(text = "ризото rice", limit = 5)).hits.isNotEmpty(),
                "the index is incremental; if this fails the fixture is wrong, not the feature",
            )
            assertNull(holderOf(g, "cook/risotto.md"), "not attached until a pass runs")

            val result = g.attachPass()
            assertEquals(1, result.attached, "the new note must be placed")

            val holder = holderOf(g, "cook/risotto.md")
            assertNotNull(holder, "the note must be reachable through a theme without a rebuild")
            assertTrue(
                holder.members.any { it.startsWith("cook/") },
                "expected the cooking theme, got ${holder.members}",
            )
            assertTrue(
                holder.members.none { it.startsWith("infra/") },
                "placed in the wrong theme — neighbour voting failed: ${holder.members}",
            )
            index.close(); g.close()
        }
    }

    @Test
    fun `the index-sync hook attaches without anyone calling the pass directly`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)

            addCookNote(fx, index)
            // This is what VaultContext chains onto IndexService.onSynced. A test that only ever
            // called attachPass() directly would pass with the hook disconnected.
            g.onIndexSynced()

            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && holderOf(g, "cook/risotto.md") == null) {
                Thread.sleep(25)
            }
            assertNotNull(holderOf(g, "cook/risotto.md"), "the hook never ran the pass")
            index.close(); g.close()
        }
    }

    @Test
    fun `attachment survives a restart`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val dir = fx.root.resolve(".svod/graph")
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(dir, fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            addCookNote(fx, index)
            assertEquals(1, g.attachPass().attached)
            g.close()

            // A fresh service over the same sidecar: the placement must have been persisted, or every
            // restart would drop the note off the map again.
            val g2 = GraphService(dir, fx.engine, index, SpyLlm(), config()).start()
            assertNotNull(holderOf(g2, "cook/risotto.md"), "the attachment was not persisted")
            assertEquals(1, g2.status().attachedCount)
            index.close(); g2.close()
        }
    }

    @Test
    fun `running the pass twice does not duplicate a member`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            addCookNote(fx, index)

            assertEquals(1, g.attachPass().attached)
            val second = g.attachPass()
            assertEquals(0, second.attached, "an already-placed note must not be placed again")

            for (level in 0 until g.status().levelCount) {
                for (c in g.communities(null, level, 500)) {
                    assertEquals(
                        c.members.size, c.members.toSet().size,
                        "duplicate members in ${c.id}: ${c.members}",
                    )
                }
            }
            assertEquals(1, g.status().attachedCount)
            index.close(); g.close()
        }
    }

    @Test
    fun `a crash between the two incremental writes cannot duplicate a member`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val dir = fx.root.resolve(".svod/graph")
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(dir, fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            addCookNote(fx, index)
            assertEquals(1, g.attachPass().attached)
            g.close()

            // saveIncremental writes communities.json first and meta.json second, so a crash between
            // them leaves the note IN a community while `attachedPaths` still says it is not placed.
            // Reproduce exactly that state; the next pass must recognise it, not add the note twice.
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val metaFile = dir.resolve("meta.json")
            val meta = json.decodeFromString<GraphMeta>(java.nio.file.Files.readString(metaFile))
            java.nio.file.Files.writeString(metaFile, json.encodeToString(meta.copy(attachedPaths = emptyList())))

            val g2 = GraphService(dir, fx.engine, index, SpyLlm(), config()).start()
            g2.attachPass()
            val holder = holderOf(g2, "cook/risotto.md")
            assertNotNull(holder)
            assertEquals(
                holder.members.size, holder.members.toSet().size,
                "the note was added a second time: ${holder.members}",
            )
            index.close(); g2.close()
        }
    }

    // ---- what attachment must NOT do ----

    @Test
    fun `attachment consults no model and never re-runs community detection`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val spy = SpyLlm()
            val detector = CountingDetector()
            val g = GraphService(
                fx.root.resolve(".svod/graph"), fx.engine, index, spy, config(), detector,
            ).start()
            g.rebuild(); awaitBuild(g)
            val callsAfterBuild = spy.calls.get()
            val detectionsAfterBuild = detector.calls.get()
            assertTrue(callsAfterBuild > 0 && detectionsAfterBuild == 1, "the build itself is expected to do both")

            addCookNote(fx, index)
            assertEquals(1, g.attachPass().attached)

            assertEquals(callsAfterBuild, spy.calls.get(), "attachment must not summarise anything")
            assertEquals(detectionsAfterBuild, detector.calls.get(), "attachment must not re-cluster")
            index.close(); g.close()
        }
    }

    @Test
    fun `attachment leaves the summary alone and discloses the growth instead`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            val before = g.communities(null, null, 500).associate { it.id to (it.summary to it.size) }

            addCookNote(fx, index)
            assertEquals(1, g.attachPass().attached)

            val grown = holderOf(g, "cook/risotto.md")
            assertNotNull(grown)
            assertEquals(before.getValue(grown.id).first, grown.summary, "the summary must not be regenerated")
            assertEquals(before.getValue(grown.id).second + 1, grown.size)
            assertEquals(1, grown.addedSinceSummary, "growth past the summary must be disclosed, not hidden")
            // Untouched communities keep a zero counter — otherwise the field says nothing.
            for (c in g.communities(null, null, 500).filter { it.id != grown.id }) {
                assertEquals(0, c.addedSinceSummary, "${c.id} was not grown")
            }
            index.close(); g.close()
        }
    }

    @Test
    fun `attachment touches neither the lucene index nor the vault, and search is unchanged`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            addCookNote(fx, index)

            val queries = listOf("tomato", "kubernetes", "rollout", "ризото")
            val docsBefore = index.docCount()
            val headBefore = index.headCommitIndexed()
            val before = queries.map { q -> index.search(SearchQuery(text = q, limit = 10)).hits.map { it.chunkId } }

            assertEquals(1, g.attachPass().attached)

            assertEquals(docsBefore, index.docCount(), "attachment must not write documents")
            assertEquals(headBefore, index.headCommitIndexed(), "attachment must not move the index head")
            assertEquals(
                before,
                queries.map { q -> index.search(SearchQuery(text = q, limit = 10)).hits.map { it.chunkId } },
                "attachment must not perturb search in any way",
            )
            assertTrue(GitCli.isWorkingTreeClean(fx.root), "the sidecar must stay out of the tracked tree")
            index.close(); g.close()
        }
    }

    @Test
    fun `a note with no close-enough neighbour stays pending rather than being filed arbitrarily`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            // Build at the normal threshold, then attach at one nothing can clear: attachment must
            // apply the same "close enough to earn an edge" rule the build applies, or it would place
            // notes the build itself would have left isolated.
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            g.close()

            val strict = GraphService(
                fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config(simThreshold = 0.999),
            ).start()
            addCookNote(fx, index)
            val result = strict.attachPass()

            assertEquals(0, result.attached, "nothing may be attached below the similarity threshold")
            assertEquals(1, result.pending)
            assertNull(holderOf(strict, "cook/risotto.md"), "an unplaceable note must stay off the map")
            assertEquals(1, strict.status().pendingCount, "and must be counted, so the operator can act")
            index.close(); strict.close()
        }
    }

    @Test
    fun `attachment can be given a lower bar than the build's edge threshold`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val dir = fx.root.resolve(".svod/graph")
            GraphService(dir, fx.engine, index, SpyLlm(), config()).start().also {
                it.rebuild(); awaitBuild(it); it.close()
            }
            addCookNote(fx, index)

            // `incremental = false` on both probes so `start()` does not also fire a pass in the
            // background: this test asserts what the THRESHOLD does, and a background pass that wins
            // the race would leave the explicit call with nothing fresh to attach and fail it for an
            // unrelated reason. A direct `attachPass()` still runs — only the hook is gated.
            val strict = GraphService(
                dir, fx.engine, index, SpyLlm(), config(incremental = false, simThreshold = 0.999),
            ).start()
            // The bar that leaves it pending — the build's own, which on the real vault is tuned
            // high enough to leave ~17% of notes off the map.
            assertEquals(0, strict.attachPass().attached)
            strict.close()

            // The same note, the same graph, attachment given its own (lower) bar. Nothing about the
            // partition changes — only whether this note gets a home in it.
            val lenient = GraphService(
                dir, fx.engine, index, SpyLlm(),
                config(incremental = false, simThreshold = 0.999, attachThreshold = 0.5),
            ).start()
            assertEquals(1, lenient.attachPass().attached, "an explicit attachThreshold must override simThreshold")
            assertNotNull(holderOf(lenient, "cook/risotto.md"))
            index.close(); lenient.close()
        }
    }

    @Test
    fun `attachment falls back to the build threshold when given no bar of its own`() {
        assertEquals(0.88, GraphConfig(simThreshold = 0.88).effectiveAttachThreshold)
        assertEquals(0.75, GraphConfig(simThreshold = 0.88, attachThreshold = 0.75).effectiveAttachThreshold)
    }

    @Test
    fun `the hook does nothing while incremental attachment is off`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(
                fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config(incremental = false),
            ).start()
            g.rebuild(); awaitBuild(g)
            addCookNote(fx, index)

            g.onIndexSynced()
            Thread.sleep(500)
            assertNull(holderOf(g, "cook/risotto.md"), "the feature is off; nothing may be attached")
            assertTrue(!g.status().incremental, "and the status must say so, or 0 counts read as 'nothing new'")
            assertEquals(0, g.status().attachedCount)
            index.close(); g.close()
        }
    }

    @Test
    fun `incremental attachment is off by default`() {
        assertTrue(!GraphConfig().incremental, "every part of this feature opts in, like the rest of the graph")
    }

    @Test
    fun `a full rebuild absorbs the attached notes and resets the bookkeeping`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            addCookNote(fx, index)
            assertEquals(1, g.attachPass().attached)
            assertEquals(1, g.status().attachedCount)

            g.rebuild(); awaitBuild(g)

            assertEquals(0, g.status().attachedCount, "the rebuild clustered it properly; it is no longer an add-on")
            assertEquals(0, g.status().pendingCount)
            assertNotNull(holderOf(g, "cook/risotto.md"), "and it is still on the map, by the real partition")
            assertTrue(
                g.communities(null, null, 500).all { it.addedSinceSummary == 0 },
                "a fresh summary describes the full membership",
            )
            index.close(); g.close()
        }
    }

    // ---- drift measure ----

    @Test
    fun `drift is zero when nothing has been attached`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            g.attachPass()
            assertEquals(0.0, g.status().driftRatio, 1e-9, "nothing attached ⇒ nothing can have drifted")
            index.close(); g.close()
        }
    }

    @Test
    fun `a freshly attached note does not count as drifted`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            addCookNote(fx, index)
            assertEquals(1, g.attachPass().attached)
            assertEquals(1, g.status().attachedCount, "the note must be inside the sampled set")

            // The note was just placed by the same vote the measure re-runs, so it must agree with
            // itself — and the vote genuinely runs here, because the pass measures against the paths
            // it just wrote. It would NOT run if the measure read the pre-pass attachedPaths, which
            // is exactly the bug this assertion caught: the metric was structurally blind to the
            // notes attached in the pass that computed it. It also exercises the self-exclusion —
            // without it the note is its own nearest neighbour at cosine 1.0 and drift is 0 by
            // construction, for the wrong reason.
            assertEquals(0.0, g.status().driftRatio, 1e-9)
            index.close(); g.close()
        }
    }

    @Test
    fun `a note whose neighbourhood moved on is counted as drifted`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            addCookNote(fx, index)
            assertEquals(1, g.attachPass().attached)
            assertEquals(0.0, g.status().driftRatio, 1e-9)

            // Rewrite the attached note to be about the OTHER topic. Attachment never re-places a
            // note it has already placed, so it keeps sitting in the cooking theme while everything
            // about its content now says infrastructure — drift, in one edit.
            editNote(
                fx, index, "cook/risotto.md",
                "# Ризото\nkubernetes cluster deployment rollout nginx proxy tls certificate",
            )
            g.attachPass()

            // The load-bearing assertion: without it the measure could be hard-coded to 0.0 and every
            // other drift test would still pass.
            assertTrue(
                g.status().driftRatio > 0.0,
                "the neighbourhood changed underneath an attached note and the measure did not notice",
            )
            index.close(); g.close()
        }
    }

    @Test
    fun `drift counts the notes attached by the same pass, not only the earlier ones`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)

            addCookNote(fx, index, "cook/risotto.md")
            assertEquals(1, g.attachPass().attached)

            // One note drifts, and a SECOND note is attached by the very pass that measures. The
            // second one cannot have drifted, so the honest ratio is 1 of 2.
            editNote(
                fx, index, "cook/risotto.md",
                "# Ризото\nkubernetes cluster deployment rollout nginx proxy tls certificate",
            )
            runBlocking { fx.seed("cook/gnocchi.md", "# Ньоки\nrecipe tomato basil pasta simmer boiling water") }
            index.reconcileNow()
            assertEquals(1, g.attachPass().attached, "the second note must attach in this pass")
            assertEquals(2, g.status().attachedCount)

            // Reading the PRE-pass attachedPaths would sample only the first note and report 1.0 —
            // the metric would be blind to everything the pass it runs in just attached, and would
            // overstate drift for as long as attachments kept arriving.
            assertEquals(
                0.5, g.status().driftRatio, 1e-9,
                "expected 1 drifted of 2 attached; a 1.0 here means the current pass's attachment was excluded",
            )
            index.close(); g.close()
        }
    }

    @Test
    fun `a full rebuild clears the drift measure with the attachment bookkeeping`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            addCookNote(fx, index)
            g.attachPass()

            g.rebuild(); awaitBuild(g)

            assertEquals(0.0, g.status().driftRatio, 1e-9, "the rebuild re-clustered everything")
            assertEquals(0, g.status().attachedCount)
            index.close(); g.close()
        }
    }

    // ---- the placement rule itself ----

    @Test
    fun `neighbour voting is weighted, so one very close note outranks several distant ones`() {
        // Unit vectors, so the dot product below IS the cosine: 1.00 for the near note against
        // 0.40 + 0.40 for the pair. Two distant notes outnumber it and still lose.
        val vectors = mapOf(
            "near.md" to floatArrayOf(1f, 0f, 0f),
            "far1.md" to floatArrayOf(0.4f, 0.9165f, 0f),
            "far2.md" to floatArrayOf(0.4f, -0.9165f, 0f),
        )
        val memberOf = mapOf("near.md" to "A", "far1.md" to "B", "far2.md" to "B")
        val neighbours = Attachment.nearest(floatArrayOf(1f, 0f, 0f), vectors, k = 3, threshold = 0.3)
        assertEquals(3, neighbours.size)
        assertEquals("near.md", neighbours.first().path)
        // A plain head-count would answer "B" (two members against one). Weighting by similarity is
        // what makes the near-identical note decide.
        assertEquals("A", Attachment.dominantCommunity(neighbours, memberOf))
    }

    @Test
    fun `neighbours below the threshold are not neighbours at all`() {
        val vectors = mapOf("other.md" to floatArrayOf(0f, 1f, 0f))
        assertTrue(Attachment.nearest(floatArrayOf(1f, 0f, 0f), vectors, k = 5, threshold = 0.5).isEmpty())
        assertNull(Attachment.dominantCommunity(emptyList(), mapOf("other.md" to "A")))
    }

    @Test
    fun `a tie is broken deterministically`() {
        val vectors = mapOf("a.md" to floatArrayOf(1f, 0f), "b.md" to floatArrayOf(1f, 0f))
        val memberOf = mapOf("a.md" to "B", "b.md" to "A")
        val neighbours = Attachment.nearest(floatArrayOf(1f, 0f), vectors, k = 2, threshold = 0.5)
        repeat(5) {
            assertEquals("A", Attachment.dominantCommunity(neighbours, memberOf), "placement must be reproducible")
        }
    }
}
