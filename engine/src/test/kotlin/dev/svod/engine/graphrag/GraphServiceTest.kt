package dev.svod.engine.graphrag

import dev.svod.engine.core.GitCli
import dev.svod.engine.index.FakeEmbedder
import dev.svod.engine.index.IndexFixture
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.index.SearchQuery
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tests that decide whether this feature was allowed to ship.
 *
 * The operator's question for this sprint was not "does the graph work" but "how far can we go
 * **without breaking Svod**". So the load-bearing assertions here are the negative ones: search is
 * unchanged, the Lucene index is untouched, the vault is never written, a build failure is invisible
 * to callers, and **no model is ever consulted at query time**.
 *
 * NB (`mem:kotlin-junit-silent-skip`): all bodies are block bodies, so JUnit collects them.
 */
class GraphServiceTest {

    /** Records every prompt so both the call COUNT and the call CONTENT can be asserted. */
    private class SpyLlm(
        private val reply: (String) -> String? = { "TITLE: Тема\nSUMMARY: Обобщение." },
        private val boom: Boolean = false,
    ) : SummaryLlm {
        override val provider = "spy"
        override val model = "spy-model"
        override val isActive = true
        val prompts = CopyOnWriteArrayList<String>()
        val calls = AtomicInteger(0)
        override suspend fun summarise(prompt: String): String? {
            calls.incrementAndGet()
            prompts.add(prompt)
            if (boom) throw IllegalStateException("summariser exploded")
            return reply(prompt)
        }
    }

    private fun config(
        enabled: Boolean = true,
        minCommunitySize: Int = 2,
        simThreshold: Double = 0.5,
        summaryInputChars: Int = 12_000,
    ) = GraphConfig(
        enabled = enabled,
        simEdgesPerNote = 4,
        simThreshold = simThreshold,
        minCommunitySize = minCommunitySize,
        summariseTopLevels = 1,
        summaryInputChars = summaryInputChars,
        rebuildOnStartup = false,
    )

    private fun awaitBuild(g: GraphService, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = g.status().state
            if (state != GraphState.BUILDING.name) return
            Thread.sleep(25)
        }
        throw AssertionError("graph build did not finish within ${timeoutMs}ms")
    }

    /** Seeds a small vault with two clearly separate topic clusters, linked within each cluster. */
    private suspend fun IndexFixture.seedTwoTopics() {
        seed("cook/pasta.md", "# Паста\nrecipe tomato basil pasta boiling water\nsee [[cook/sauce.md]]")
        seed("cook/sauce.md", "# Сос\nrecipe tomato basil sauce simmer\nsee [[cook/pasta.md]]")
        seed("cook/salad.md", "# Салата\nrecipe tomato basil salad fresh")
        seed("infra/deploy.md", "# Deploy\nkubernetes cluster deployment rollout\nsee [[infra/nginx.md]]")
        seed("infra/nginx.md", "# Nginx\nkubernetes cluster nginx proxy rollout\nsee [[infra/deploy.md]]")
        seed("infra/tls.md", "# TLS\nkubernetes cluster tls certificate rollout")
    }

    // ---- A2/A3: search must not change ----

    @Test
    fun `search results are identical before and after a graph build`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val queries = listOf("tomato", "kubernetes", "rollout", "паста")
            val before = queries.map { q -> index.search(SearchQuery(text = q, limit = 10)).hits.map { it.chunkId } }

            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            assertTrue(g.rebuild(), "rebuild must start when the feature is enabled")
            awaitBuild(g)
            assertEquals(GraphState.READY.name, g.status().state, g.status().error ?: "")

            val after = queries.map { q -> index.search(SearchQuery(text = q, limit = 10)).hits.map { it.chunkId } }
            assertEquals(before, after, "a built graph must not perturb search results in any way")
            index.close()
            g.close()
        }
    }

    // ---- A4: the Lucene index is read, never written ----

    @Test
    fun `a graph build leaves the lucene index untouched`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val docsBefore = index.docCount()
            val headBefore = index.headCommitIndexed()

            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)

            assertEquals(docsBefore, index.docCount(), "graph build must not add or remove index docs")
            assertEquals(headBefore, index.headCommitIndexed(), "graph build must not move the index head")
            index.close()
            g.close()
        }
    }

    // ---- A8: the vault is never written ----

    @Test
    fun `a graph build never writes to the vault`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)

            assertTrue(GitCli.isWorkingTreeClean(fx.root), "the graph sidecar must stay out of the tracked tree")
            index.close()
            g.close()
        }
    }

    // ---- A5: a build failure is invisible to callers ----

    @Test
    fun `a failing build reports ERROR and leaves search working`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val exploding = object : CommunityDetector {
                override fun detect(graph: NoteGraph): List<Partition> = error("detector exploded")
            }
            val g = GraphService(
                fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config(), exploding,
            ).start()
            g.rebuild(); awaitBuild(g)

            assertEquals(GraphState.ERROR.name, g.status().state)
            assertNotNull(g.status().error, "the failure must be surfaced, not swallowed silently")
            assertTrue(index.search(SearchQuery(text = "tomato", limit = 5)).hits.isNotEmpty(), "search must survive")
            assertTrue(g.communities("tomato").isEmpty(), "a failed build serves nothing, but does not throw")
            index.close()
            g.close()
        }
    }

    // ---- B2: no vectors ⇒ wikilink edges only ----

    @Test
    fun `without an embedder the graph is built from wikilinks alone`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(NoneEmbedder)
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)

            val status = g.status()
            assertEquals(GraphState.READY.name, status.state, status.error ?: "")
            assertEquals(0, status.simEdgeCount, "a BM25-only vault can have no similarity edges")
            assertTrue(status.linkEdgeCount > 0, "the wikilinks are still there")
            assertEquals(0.0, status.vectorCoverage, 1e-9, "coverage must report the truth, not hide it")
            index.close()
            g.close()
        }
    }

    // ---- D1: THE invariant — no model at query time ----

    @Test
    fun `no model is consulted on any query path`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val spy = SpyLlm()
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, spy, config()).start()
            g.rebuild(); awaitBuild(g)

            val afterBuild = spy.calls.get()
            assertTrue(afterBuild > 0, "the build itself is expected to summarise")

            repeat(3) {
                g.communities("tomato basil")
                g.communities(null, 0, 10)
                g.communities("kubernetes cluster", 0, 5)
                g.status()
                g.community("L0-0")
            }
            assertEquals(afterBuild, spy.calls.get(), "query time must never call the summariser")
            index.close()
            g.close()
        }
    }

    // ---- D2: the privacy guard reaches the prompt ----

    @Test
    fun `private spans never reach the summary prompt`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                fx.seedTwoTopics()
                fx.seed(
                    "cook/secret.md",
                    "# Тайна\nrecipe tomato basil pasta\n<private>SUPER_SECRET_TOKEN_42</private>\nmore recipe text",
                )
            }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val spy = SpyLlm()
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, spy, config()).start()
            g.rebuild(); awaitBuild(g)

            assertTrue(spy.prompts.isNotEmpty(), "the guard is only meaningful if a prompt was built")
            for (p in spy.prompts) {
                assertTrue(
                    "SUPER_SECRET_TOKEN_42" !in p,
                    "a <private> span leaked into a model prompt — this is the leak guard failing",
                )
            }
            index.close()
            g.close()
        }
    }

    // ---- D3/D5: a broken summariser degrades, never fails ----

    @Test
    fun `a null summary falls back to a machine label and the build still succeeds`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(
                fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(reply = { null }), config(),
            ).start()
            g.rebuild(); awaitBuild(g)

            assertEquals(GraphState.READY.name, g.status().state, g.status().error ?: "")
            val found = g.communities(null, null, 20)
            assertTrue(found.isNotEmpty(), "communities exist regardless of the summariser")
            assertTrue(found.all { it.summary == null }, "no summary is honest; a fabricated one would not be")
            assertTrue(found.all { it.title.isNotBlank() }, "every community still needs a usable label")
            index.close()
            g.close()
        }
    }

    @Test
    fun `a throwing summariser is contained and the build completes`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(
                fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(boom = true), config(),
            ).start()
            g.rebuild(); awaitBuild(g)

            // The spy throws rather than returning null, which is the harsher failure: it must be
            // caught at the call site, not merely tolerated as a null result.
            assertEquals(GraphState.READY.name, g.status().state, g.status().error ?: "")
            assertTrue(g.communities(null, null, 20).isNotEmpty())
            index.close()
            g.close()
        }
    }

    // ---- D6: the call count is bounded by communities, not notes ----

    @Test
    fun `summary calls are bounded by community count, not note or chunk count`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val spy = SpyLlm()
            val cfg = config(minCommunitySize = 2)
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, spy, cfg).start()
            g.rebuild(); awaitBuild(g)

            val coarsest = g.status().levelCount - 1
            val eligible = g.communities(null, coarsest, 500).count { it.size >= cfg.minCommunitySize }
            assertEquals(eligible, spy.calls.get(), "one call per eligible community at the summarised level")
            assertTrue(spy.calls.get() < index.docCount(), "must never approach a call per chunk")
            index.close()
            g.close()
        }
    }

    // ---- F1/F5: the query surface ----

    @Test
    fun `a query ranks the semantically closest community first`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)

            val top = g.communities("kubernetes cluster rollout", null, 5).firstOrNull()
            assertNotNull(top, "a built graph must answer a query")
            assertTrue(
                top.members.any { it.startsWith("infra/") },
                "expected the infra cluster to rank first, got ${top.members}",
            )
            index.close()
            g.close()
        }
    }

    @Test
    fun `an unbuilt graph returns empty rather than failing`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()

            assertEquals(GraphState.NOT_BUILT.name, g.status().state)
            assertTrue(g.communities("anything").isEmpty())
            assertTrue(g.community("L0-0") == null)
            index.close()
            g.close()
        }
    }

    // ---- F4: staleness is surfaced, not hidden ----

    @Test
    fun `a commit after the build marks the graph stale but keeps serving`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(), config()).start()
            g.rebuild(); awaitBuild(g)
            assertTrue(!g.status().stale, "freshly built graph is not stale")

            runBlocking { fx.seed("cook/new.md", "recipe tomato basil something new") }
            // Staleness is measured against the INDEX head, so let the index catch up first —
            // otherwise this asserts on a race, not on the behaviour.
            index.reconcileNow()

            assertTrue(g.status().stale, "the index moved past the build — that must be visible")
            assertTrue(g.communities(null, null, 5).isNotEmpty(), "stale results are still served")
            index.close()
            g.close()
        }
    }

    // ---- labels and prompt honesty (both found by validating against a copy of the real vault) ----

    @Test
    fun `a community without a summary is labelled by its deepest shared folder`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                // Same shape as the real vault: many files sharing a deep prefix but different names.
                for (i in 1..5) fx.seed("ai-prompts/03-custom-skills/ex$i/SKILL.md", "skill guide steps example $i")
            }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(
                fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(reply = { null }), config(),
            ).start()
            g.rebuild(); awaitBuild(g)

            val titles = g.communities(null, null, 20).map { it.title }
            assertTrue(titles.isNotEmpty(), "expected at least one community")
            // The naive fallback returned "SKILL" here — the first member's filename, which describes
            // none of the others. The label must identify the GROUP.
            assertTrue(
                titles.none { it == "SKILL" },
                "a bare filename is not a usable community label; got $titles",
            )
            assertTrue(
                titles.any { it.contains("ai-prompts") },
                "expected a shared-folder label, got $titles",
            )
            index.close()
            g.close()
        }
    }

    @Test
    fun `community labels are unique within a level`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                // Two clusters whose only shared prefix is `projects`, each dominated by the SAME
                // next segment — the exact shape that defeated the first disambiguation attempt on
                // the real vault.
                for (i in 1..4) fx.seed("projects/skb2/alpha/n$i.md", "alpha kubernetes rollout cluster $i")
                for (i in 1..4) fx.seed("projects/skb2/beta/n$i.md", "beta tomato basil recipe $i")
                for (i in 1..4) fx.seed("projects/other/n$i.md", "gamma sparkle notarize release $i")
            }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(
                fx.root.resolve(".svod/graph"), fx.engine, index, SpyLlm(reply = { null }), config(),
            ).start()
            g.rebuild(); awaitBuild(g)

            for (level in 0 until g.status().levelCount) {
                val titles = g.communities(null, level, 500).map { it.title }
                assertEquals(
                    titles.size, titles.toSet().size,
                    "level $level has duplicate labels, which a list UI cannot disambiguate: $titles",
                )
            }
            index.close()
            g.close()
        }
    }

    @Test
    fun `a prompt that can only fit part of a community says so`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val spy = SpyLlm()
            // Small enough that only the first (short) fixture note fits, while every community has
            // at least two members — which is what makes the disclosure mandatory.
            val g = GraphService(
                fx.root.resolve(".svod/graph"), fx.engine, index, spy, config(summaryInputChars = 150),
            ).start()
            g.rebuild(); awaitBuild(g)

            assertTrue(spy.prompts.isNotEmpty(), "the guard is only meaningful if a prompt was built")
            // Without this disclosure the model describes hundreds of notes from a handful and the
            // operator has no way to tell — a fabrication the engine would be responsible for.
            assertTrue(
                spy.prompts.any { it.contains("ВНИМАНИЕ") && it.contains("от общо") },
                "a truncated community must tell the model it is seeing a sample",
            )
            index.close()
            g.close()
        }
    }

    // ---- disabled means disabled ----

    @Test
    fun `a disabled graph never builds`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedTwoTopics() }
            val index = fx.newIndex(FakeEmbedder("fake"))
            val spy = SpyLlm()
            val g = GraphService(
                fx.root.resolve(".svod/graph"), fx.engine, index, spy, config(enabled = false),
            ).start()

            assertTrue(!g.rebuild(), "rebuild must refuse while the feature is off")
            assertEquals(GraphState.NOT_BUILT.name, g.status().state)
            assertEquals(0, spy.calls.get(), "a disabled feature must not touch a model at all")
            index.close()
            g.close()
        }
    }
}
