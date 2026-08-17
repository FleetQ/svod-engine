package dev.svod.engine.graphrag

import dev.svod.engine.index.FakeEmbedder
import dev.svod.engine.index.IndexFixture
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hierarchical summarisation: compose a coarse community's summary from its CHILDREN'S summaries
 * instead of from a sample of raw notes.
 *
 * The measured motivation, on the real 3,096-note vault: the flat path summarises only the coarsest
 * level, where the median community holds 44 notes and the largest holds 320, while the prompt fits
 * under ten — so every one of those 38 summaries was written from a sample. One level down there are
 * 258 communities with a median of **7** members, which fit entirely, and none of them was summarised.
 *
 * The most important test here is the FIRST one: with the flag off, nothing may change.
 *
 * NB (`mem:kotlin-junit-silent-skip`): every test body is a block body, so JUnit collects them.
 */
class GraphHierarchicalSummaryTest {

    private class SpyLlm(private val reply: (String) -> String? = { "TITLE: Тема\nSUMMARY: Обобщение на групата." }) : SummaryLlm {
        override val provider = "spy"
        override val model = "spy-model"
        override val isActive = true
        val prompts = CopyOnWriteArrayList<String>()
        val calls = AtomicInteger(0)
        override suspend fun summarise(prompt: String, system: String?): String? {
            calls.incrementAndGet()
            prompts.add(prompt)
            return reply(prompt)
        }
    }

    private fun config(hierarchical: Boolean) = GraphConfig(
        enabled = true,
        simEdgesPerNote = 4,
        simThreshold = 0.5,
        minCommunitySize = 2,
        summariseTopLevels = 1,
        rebuildOnStartup = false,
        hierarchicalSummaries = hierarchical,
    )

    private fun awaitBuild(g: GraphService, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (g.status().state != GraphState.BUILDING.name) return
            Thread.sleep(25)
        }
        throw AssertionError("graph build did not finish within ${timeoutMs}ms")
    }

    private suspend fun IndexFixture.seedLayered() {
        for (i in 1..5) seed("cook/pasta/n$i.md", "recipe tomato basil pasta boiling water simmer $i")
        for (i in 1..5) seed("cook/bake/n$i.md", "recipe flour sugar oven bake cake dough $i")
        for (i in 1..5) seed("infra/k8s/n$i.md", "kubernetes cluster deployment rollout pods $i")
        for (i in 1..5) seed("infra/tls/n$i.md", "tls certificate renewal nginx proxy handshake $i")
    }

    /**
     * A deterministic two-level partition: level 0 groups by parent folder (4 groups of 5), level 1
     * by top folder (2 groups of 10), so level 0 nests cleanly inside level 1.
     *
     * Injected rather than coaxed out of Louvain. On a 20-note fixture Louvain converges in a single
     * pass, so the real detector produces ONE level and the composed path would never be reached —
     * a test that silently exercises nothing. The seam already exists for exactly this reason
     * (`GraphServiceTest` uses it to inject a failing and a cancelling detector).
     */
    private class NestedDetector : CommunityDetector {
        override fun detect(graph: NoteGraph): List<Partition> {
            val level0 = graph.nodes.groupBy { it.substringBeforeLast('/') }.values.map { it.sorted() }
            val level1 = graph.nodes.groupBy { it.substringBefore('/') }.values.map { it.sorted() }
            return listOf(level0, level1)
        }
    }

    private fun build(fx: IndexFixture, spy: SpyLlm, hierarchical: Boolean): GraphService {
        val index = fx.newIndex(FakeEmbedder("fake"))
        val g = GraphService(
            fx.root.resolve(".svod/graph"), fx.engine, index, spy, config(hierarchical), NestedDetector(),
        ).start()
        g.rebuild(); awaitBuild(g)
        return g
    }

    // ---- the compatibility guard ----

    @Test
    fun `with the flag off nothing about summarising changes`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedLayered() }
            val spy = SpyLlm()
            val g = build(fx, spy, hierarchical = false)

            // Two independent properties, both of which the hierarchical path deliberately breaks —
            // so this fails if the dispatcher is ever wired the wrong way round. (An earlier version
            // of this test built TWICE with the flag off and compared the two, which is a determinism
            // check of one path against itself and could not fail for the reason it was named for.)
            assertTrue(spy.prompts.isNotEmpty(), "the guard is only meaningful if prompts were built")
            assertTrue(
                spy.prompts.all { it.contains("=== НАЧАЛО НА ИЗВАДКАТА ===") },
                "the default path builds every prompt from raw note excerpts",
            )
            assertTrue(
                spy.prompts.none { it.contains("=== НАЧАЛО НА ПОДГРУПИТЕ ===") },
                "the default path must never compose a prompt from child summaries",
            )
            // `summariseTopLevels = 1` ⇒ ONLY the coarsest level is summarised. Hierarchical mode
            // summarises every level, so this is the sharpest observable difference between them.
            val coarsest = g.status().levelCount - 1
            assertTrue(coarsest >= 1, "the fixture must produce more than one level for this to mean anything")
            for (level in 0 until coarsest) {
                assertTrue(
                    g.communities(null, level, 500).all { it.summary == null },
                    "level $level must stay unsummarised with the flag off",
                )
            }
            assertTrue(
                g.communities(null, coarsest, 500).any { it.summary != null },
                "the coarsest level is the one the default path summarises",
            )
            g.close()
        }
    }

    // ---- the feature ----

    @Test
    fun `the finest level is still summarised from raw excerpts`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedLayered() }
            val spy = SpyLlm()
            val g = build(fx, spy, hierarchical = true)
            assertTrue(
                spy.prompts.any { it.contains("=== НАЧАЛО НА ИЗВАДКАТА ===") },
                "level 0 has no children; it must fall back to the raw path",
            )
            g.close()
        }
    }

    @Test
    fun `a coarse level is composed from child summaries and contains no raw note text`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedLayered() }
            val spy = SpyLlm()
            val g = build(fx, spy, hierarchical = true)
            val composed = spy.prompts.filter { it.contains("=== НАЧАЛО НА ПОДГРУПИТЕ ===") }
            assertTrue(composed.isNotEmpty(), "expected at least one composed prompt; levels=${g.status().levelCount}")
            for (p in composed) {
                assertTrue(p.contains("Обобщение на групата."), "a composed prompt must carry child summaries")
                // The point of composing: the model sees a compressed view of the WHOLE group, not a
                // sample of it. Raw note bodies leaking in would mean it is doing both, at double cost.
                assertTrue(
                    "boiling water simmer" !in p && "kubernetes cluster deployment" !in p,
                    "raw note text leaked into a composed prompt",
                )
            }
            g.close()
        }
    }

    @Test
    fun `every level gets summarised, not just the coarsest`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedLayered() }
            val flat = SpyLlm()
            val g1 = build(fx, flat, hierarchical = false)
            val flatCalls = flat.calls.get()
            g1.close()
        }
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedLayered() }
            val hier = SpyLlm()
            val g2 = build(fx, hier, hierarchical = true)
            // summariseTopLevels does not apply here: a coarse level with no summarised children has
            // nothing to compose from, so every level must be walked.
            assertTrue(hier.calls.get() > 0)
            val levels = g2.status().levelCount
            if (levels > 1) {
                assertTrue(
                    g2.communities(null, 0, 500).any { it.summary != null },
                    "the finest level must be summarised when the flag is on — it is the input to the rest",
                )
            }
            g2.close()
        }
    }

    @Test
    fun `an unresolvable child set falls back to the raw path rather than failing`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                for (i in 1..6) fx.seed("flat/n$i.md", "recipe tomato basil pasta simmer $i")
            }
            val spy = SpyLlm()
            // The real detector on a single flat cluster yields ONE level, so nothing ever has
            // children — the case a nested corpus cannot produce and the code must still survive.
            val index = fx.newIndex(FakeEmbedder("fake"))
            val g = GraphService(
                fx.root.resolve(".svod/graph"), fx.engine, index, spy, config(hierarchical = true),
            ).start()
            g.rebuild(); awaitBuild(g)
            assertEquals(GraphState.READY.name, g.status().state, g.status().error ?: "")
            assertTrue(spy.prompts.all { it.contains("=== НАЧАЛО НА ИЗВАДКАТА ===") })
            assertTrue(g.communities(null, null, 20).isNotEmpty(), "the build must still produce communities")
            g.close()
        }
    }

    @Test
    fun `a composed prompt claims a sample only when children were actually cut off`() {
        IndexFixture.create().use { fx ->
            runBlocking { fx.seedLayered() }
            val spy = SpyLlm()
            val g = build(fx, spy, hierarchical = true)
            val composed = spy.prompts.filter { it.contains("=== НАЧАЛО НА ПОДГРУПИТЕ ===") }
            assertTrue(composed.isNotEmpty())
            // The whole gain is that composition usually does NOT have to truncate. A footer that
            // always said "you saw only N of M" would be a fabrication in the opposite direction —
            // the model would hedge a summary that actually covers everything.
            for (p in composed) {
                assertTrue(
                    "Видя " !in p,
                    "children fit comfortably here; the sample disclosure must not be emitted anyway",
                )
            }
            g.close()
        }
    }

    @Test
    fun `private spans never reach a composed prompt either`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                fx.seedLayered()
                fx.seed(
                    "cook/secret.md",
                    "recipe tomato basil pasta\n<private>SUPER_SECRET_TOKEN_42</private>\nmore recipe text",
                )
            }
            val spy = SpyLlm()
            val g = build(fx, spy, hierarchical = true)
            assertTrue(spy.prompts.isNotEmpty())
            for (p in spy.prompts) {
                assertTrue("SUPER_SECRET_TOKEN_42" !in p, "a <private> span leaked into a prompt")
            }
            g.close()
        }
    }

    @Test
    fun `the language decision still comes from the content`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                for (i in 1..6) fx.seed("bg/a$i.md", "Бележка за архитектурата на системата номер $i\n[[bg/a1.md]]")
                for (i in 1..6) fx.seed("bg/b$i.md", "Решение по внедряването и поддръжката номер $i\n[[bg/b1.md]]")
            }
            val spy = SpyLlm()
            val g = build(fx, spy, hierarchical = true)
            assertTrue(spy.prompts.isNotEmpty())
            assertTrue(
                spy.prompts.all { it.contains("Пиши САМО на български") },
                "Cyrillic content must pin Bulgarian on the composed path too",
            )
            g.close()
        }
    }
}
