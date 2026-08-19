package dev.svod.engine.graphrag

import dev.svod.engine.lifecycle.ApiCompatibility
import dev.svod.engine.lifecycle.SvodConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests for the graph substrate: edge canonicalisation, Louvain determinism, and sidecar
 * persistence. No engine, no index, no model — everything here is a function of its inputs, which is
 * exactly why these are the tests that can assert *determinism* at all.
 *
 * NB (see `mem:kotlin-junit-silent-skip`): every test below uses a BLOCK body, so its return type is
 * `Unit` and JUnit actually collects it. A `@Test fun x() = runBlocking { ... }` ending in an
 * expression is silently never run.
 */
class GraphRagUnitTest {

    // ---- A1: default-off ----

    @Test
    fun `graph feature is disabled by default`() {
        val config = SvodConfig()
        assertTrue(!config.graph.enabled, "graph must be OFF in a default config")
        assertEquals("none", config.graph.summaryProvider, "no summary provider by default")

        val runtime = config.toGraphConfig()
        assertTrue(!runtime.enabled)
        assertEquals(SummaryProvider.NONE, runtime.summary.provider)
        assertTrue(!SummaryLlms.create(runtime.summary).isActive, "default summariser must be inert")
    }

    // ---- G1: contract version ----

    @Test
    fun `contract version was bumped for the additive graph routes`() {
        assertEquals("0.28.0", ApiCompatibility.CURRENT_CONTRACT_VERSION)
    }

    // ---- B4/B5: edge canonicalisation ----

    @Test
    fun `edges are canonicalised, deduped and deterministically ordered`() {
        val raw = listOf(
            NoteEdge("b.md", "a.md", EdgeKind.SIM, 0.80),   // reversed endpoints
            NoteEdge("a.md", "b.md", EdgeKind.SIM, 0.90),   // same pair, stronger
            NoteEdge("a.md", "a.md", EdgeKind.LINK, 1.0),   // self-edge: dropped
            NoteEdge("c.md", "a.md", EdgeKind.LINK, 1.5),
        )
        val g = NoteGraph.of(listOf("a.md", "b.md", "c.md"), raw)

        assertEquals(2, g.edges.size, "self-edge dropped and the duplicate pair collapsed")
        val ab = g.edges.single { it.a == "a.md" && it.b == "b.md" }
        assertEquals(0.90, ab.weight, 1e-9, "the stronger of two same-kind edges wins")
        assertEquals(listOf("a.md", "b.md", "c.md"), g.nodes)
        // Deterministic order is what makes a rebuild over an unchanged vault byte-identical.
        assertEquals(g.edges, g.edges.sortedWith(compareBy({ it.a }, { it.b })))
    }

    @Test
    fun `a wikilink edge outranks a similarity edge between the same pair`() {
        val g = NoteGraph.of(
            listOf("a.md", "b.md"),
            listOf(
                NoteEdge("a.md", "b.md", EdgeKind.SIM, 0.99),
                NoteEdge("a.md", "b.md", EdgeKind.LINK, 1.5),
            ),
        )
        assertEquals(1, g.edges.size)
        assertEquals(EdgeKind.LINK, g.edges.single().kind, "human intent must not be masked by inference")
    }

    // ---- C1: determinism ----

    @Test
    fun `louvain returns an identical partition across repeated runs`() {
        val g = twoTriangles()
        val detector = LouvainDetector()
        val first = detector.detect(g)
        repeat(4) {
            assertEquals(first, LouvainDetector().detect(g), "Louvain must not depend on RNG or map order")
        }
    }

    // ---- C2: disjoint components ----

    @Test
    fun `louvain never merges two disconnected cliques`() {
        val levels = LouvainDetector().detect(twoTriangles())
        assertTrue(levels.isNotEmpty(), "a graph with edges must produce at least one level")
        val coarsest = levels.last()
        for (community in coarsest) {
            val prefixes = community.map { it.first() }.toSet()
            assertEquals(1, prefixes.size, "community $community mixes two disconnected components")
        }
    }

    // ---- C3: every level partitions every node ----

    @Test
    fun `every hierarchy level partitions the whole corpus exactly once`() {
        val g = twoTriangles()
        val levels = LouvainDetector().detect(g)
        for (level in levels) {
            val flat = level.flatten()
            assertEquals(g.nodes.size, flat.size, "level lost or duplicated nodes")
            assertEquals(g.nodes.toSet(), flat.toSet())
        }
    }

    // ---- C5: degenerate inputs ----

    @Test
    fun `isolated notes and an edgeless graph are tolerated`() {
        val edgeless = NoteGraph.of(listOf("x.md", "y.md"), emptyList())
        val levels = LouvainDetector().detect(edgeless)
        assertEquals(1, levels.size, "an edgeless graph has exactly one honest partition")
        assertEquals(2, levels.single().size, "each note is its own community")

        assertTrue(LouvainDetector().detect(NoteGraph.of(emptyList(), emptyList())).isEmpty())
    }

    @Test
    fun `a note with no edges still appears in the partition`() {
        val g = NoteGraph.of(
            listOf("a.md", "b.md", "lonely.md"),
            listOf(NoteEdge("a.md", "b.md", EdgeKind.LINK, 1.5)),
        )
        val flat = LouvainDetector().detect(g).last().flatten()
        assertTrue("lonely.md" in flat, "an unlinked note must not be silently dropped from the corpus")
    }

    // ---- A7: sidecar corruption ----

    @Test
    fun `a corrupt sidecar loads as never-built rather than throwing`() {
        val dir = Files.createTempDirectory("svod-graph-store-")
        val store = GraphStore(dir)
        assertNull(store.load(), "nothing written yet")

        Files.writeString(dir.resolve("meta.json"), "{ this is not json")
        assertNull(store.load(), "a corrupt meta must read as NOT_BUILT, never an exception")

        Files.writeString(dir.resolve("meta.json"), """{"version":999,"head":null,"builtAt":0,"noteCount":0,"edgeCount":0,"linkEdgeCount":0,"simEdgeCount":0,"vectorCoverage":0.0,"summaryProvider":"none","summarisedCount":0}""")
        assertNull(store.load(), "an unknown sidecar layout must invalidate, not half-load")
    }

    @Test
    fun `an overwrite interrupted mid-save reads as never-built, not as mixed data`() {
        val dir = Files.createTempDirectory("svod-graph-store-")
        val store = GraphStore(dir)
        val graph = twoTriangles()
        val old = listOf(
            CommunityLevel(0, listOf(Community("L0-0", 0, listOf("a1.md", "a2.md"), title = "OLD"))),
        )
        store.save(meta(graph, "head-one"), graph, old, emptyMap())
        assertNotNull(store.load(), "first save must load")

        // Simulate a crash PART-WAY through a second save: the payload files have been replaced but
        // meta.json was never rewritten. Before the fix, the stale meta made this load cleanly and
        // hand back communities describing a graph that no longer existed.
        Files.writeString(
            dir.resolve("communities.json"),
            json(listOf(CommunityLevel(0, listOf(Community("L0-0", 0, listOf("gone.md"), title = "NEW"))))),
        )
        Files.deleteIfExists(dir.resolve("meta.json"))

        assertNull(
            store.load(),
            "an interrupted overwrite must read as NOT_BUILT; serving mixed data is worse than none",
        )
    }

    private fun meta(graph: NoteGraph, head: String) = GraphMeta(
        head = head, builtAt = 1L, noteCount = graph.nodes.size, edgeCount = graph.edges.size,
        linkEdgeCount = graph.linkEdgeCount, simEdgeCount = graph.simEdgeCount,
        vectorCoverage = 1.0, summaryProvider = "none", summarisedCount = 0,
    )

    private fun json(levels: List<CommunityLevel>): String =
        kotlinx.serialization.json.Json.encodeToString(levels)

    @Test
    fun `sidecar survives a save-load round trip`() {
        val dir = Files.createTempDirectory("svod-graph-store-")
        val store = GraphStore(dir)
        val graph = twoTriangles()
        val levels = listOf(
            CommunityLevel(
                level = 0,
                communities = listOf(
                    Community(id = "L0-0", level = 0, members = listOf("a1.md", "a2.md"), title = "A", summary = "about A"),
                ),
            ),
        )
        val meta = GraphMeta(
            head = "deadbeef", builtAt = 42L, noteCount = graph.nodes.size, edgeCount = graph.edges.size,
            linkEdgeCount = graph.linkEdgeCount, simEdgeCount = graph.simEdgeCount,
            vectorCoverage = 0.5, summaryProvider = "none", summarisedCount = 1,
        )
        store.save(meta, graph, levels, mapOf("L0-0" to floatArrayOf(0.6f, 0.8f)))

        val loaded = assertNotNull(store.load(), "a freshly saved sidecar must load")
        assertEquals("deadbeef", loaded.meta.head)
        assertEquals(graph.nodes, loaded.graph.nodes)
        assertEquals(graph.edges, loaded.graph.edges)
        assertEquals("about A", loaded.levels.single().communities.single().summary)
        assertEquals(0.8f, loaded.centroids.getValue("L0-0")[1])
        // Adjacency is rebuilt from the serialised edges, not persisted — guards the transient split.
        assertEquals(graph.degrees.toList(), loaded.graph.degrees.toList())
    }

    // ---- property sweep: the contraction loop is the part most likely to be subtly wrong ----

    @Test
    fun `over many random graphs every level stays a valid partition`() {
        // Seeded LCG, not Random(): the generator must be reproducible so a failure is debuggable.
        // (The ALGORITHM has no randomness — only this fixture does.)
        var seed = 0x5EEDL
        fun next(bound: Int): Int {
            seed = (seed * 6364136223846793005L + 1442695040888963407L) ushr 1
            return ((seed % bound) + bound).toInt() % bound
        }

        repeat(40) { trial ->
            val n = 2 + next(60)
            val nodes = (0 until n).map { "n%03d.md".format(it) }
            val edges = ArrayList<NoteEdge>()
            repeat(next(n * 3) + 1) {
                val a = next(n)
                val b = next(n)
                if (a != b) {
                    val kind = if (next(2) == 0) EdgeKind.LINK else EdgeKind.SIM
                    edges.add(NoteEdge(nodes[a], nodes[b], kind, 0.5 + next(50) / 100.0))
                }
            }
            val graph = NoteGraph.of(nodes, edges)
            val levels = LouvainDetector().detect(graph)

            assertTrue(levels.isNotEmpty(), "trial $trial produced no levels for $n nodes")
            var previousCount = Int.MAX_VALUE
            for ((i, level) in levels.withIndex()) {
                val flat = level.flatten()
                assertEquals(
                    graph.nodes.size, flat.size,
                    "trial $trial level $i lost or duplicated nodes (seed 0x5EED, n=$n)",
                )
                assertEquals(
                    graph.nodes.toSet(), flat.toSet(),
                    "trial $trial level $i does not cover exactly the node set",
                )
                assertTrue(
                    level.all { it.isNotEmpty() },
                    "trial $trial level $i contains an empty community",
                )
                // Contraction must never SPLIT a level; coarser levels have fewer communities.
                assertTrue(
                    level.size <= previousCount,
                    "trial $trial level $i grew from $previousCount to ${level.size}",
                )
                previousCount = level.size
            }
            // Determinism has to hold on every shape, not just the hand-built fixture.
            assertEquals(levels, LouvainDetector().detect(graph), "trial $trial was not deterministic")
        }
    }

    /** Two triangles that share no edge: `a1 a2 a3` and `b1 b2 b3`. */
    private fun twoTriangles(): NoteGraph {
        val edges = mutableListOf<NoteEdge>()
        for (p in listOf("a", "b")) {
            for ((x, y) in listOf(1 to 2, 2 to 3, 1 to 3)) {
                edges.add(NoteEdge("$p$x.md", "$p$y.md", EdgeKind.LINK, 1.5))
            }
        }
        return NoteGraph.of((1..3).flatMap { listOf("a$it.md", "b$it.md") }, edges)
    }
}
