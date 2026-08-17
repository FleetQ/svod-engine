package dev.svod.engine.mcp

import dev.svod.engine.graphrag.Community
import dev.svod.engine.graphrag.CommunityLevel
import dev.svod.engine.graphrag.GraphConfig
import dev.svod.engine.graphrag.GraphService
import dev.svod.engine.graphrag.MEMBER_SAMPLE
import dev.svod.engine.graphrag.NoneSummaryLlm
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an AGENT pays and receives.
 *
 * The first shipped version of `graph_communities` returned every member path of every community.
 * Measured on the operator's 3,096-note vault that was **≈44,300 tokens for one default call**, of
 * which 97% were paths and ~1,200 were the titles and summaries an agent actually reasons over. A
 * tool that costs more context than it returns signal is one an agent is right to never call, and no
 * amount of prompt guidance fixes that — the shape has to change.
 *
 * These tests pin the shape, because it is the difference between a usable tool and an unusable one.
 */
class GraphAgentErgonomicsTest {

    /**
     * Two hub-and-spoke clusters, wired with WIKILINKS rather than left to embedding similarity.
     *
     * The cap under test only bites on a community larger than [MEMBER_SAMPLE], and clustering by
     * similarity gave Louvain groups of 2-3 here no matter how the thresholds were tuned — the test
     * then passed vacuously. Explicit links make the community structure a property of the fixture
     * instead of a property of the fake embedder.
     */
    private suspend fun McpFixture.seedWideVault() {
        tools.write(write, "alpha/hub.md", "# Alpha hub\nkubernetes cluster rollout", null)
        tools.write(write, "beta/hub.md", "# Beta hub\ntomato basil pasta", null)
        for (i in 1..40) tools.write(write, "alpha/n$i.md", "# Alpha $i\nkubernetes rollout $i\n[[alpha/hub.md]]", null)
        for (i in 1..40) tools.write(write, "beta/n$i.md", "# Beta $i\ntomato basil $i\n[[beta/hub.md]]", null)
        index.reconcileNow()
    }

    private fun graphFor(fx: McpFixture): GraphService =
        GraphService(
            fx.root.resolve(".svod/graph"),
            fx.engine,
            fx.index,
            NoneSummaryLlm,
            // Densely connected on purpose: the point of these tests is the member CAP, which only
            // gets exercised by communities larger than MEMBER_SAMPLE. With few neighbours per note
            // Louvain splits the fixture into groups of 2-3 and the cap is never reached.
            GraphConfig(enabled = true, simEdgesPerNote = 20, simThreshold = 0.1, minCommunitySize = 2),
        ).start()

    private fun awaitBuild(g: GraphService) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && g.status().state == "BUILDING") Thread.sleep(25)
    }

    @Test
    fun `the listing previews members instead of dumping them`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seedWideVault()
                val g = graphFor(fx)
                g.rebuild(); awaitBuild(g)
                val tools = SvodTools(fx.engine, fx.index, fx.audit, RateLimiter.default(), fx.eventBus, graph = g)

                val res = tools.graphCommunities(fx.read, null, null, 20)
                val communities = res.data["communities"]!!.jsonArray.map { it.jsonObject }
                assertTrue(communities.isNotEmpty(), "expected communities to list")
                // Without a community bigger than the cap this test would pass vacuously, so assert
                // the fixture actually exercises the thing under test.
                assertTrue(
                    communities.any { it["size"]!!.jsonPrimitive.content.toInt() > MEMBER_SAMPLE },
                    "fixture produced no community larger than the $MEMBER_SAMPLE-path preview, so the cap is untested",
                )

                for (c in communities) {
                    assertTrue("members" !in c, "the bulk listing must NOT carry full member lists: ${c.keys}")
                    val sample = c["sampleMembers"]!!.jsonArray
                    assertTrue(
                        sample.size <= MEMBER_SAMPLE,
                        "preview must be capped at $MEMBER_SAMPLE, got ${sample.size}",
                    )
                    val size = c["size"]!!.jsonPrimitive.content.toInt()
                    // `size` stays the TRUTH even though the preview is short — an agent must not be
                    // misled into thinking a 40-note theme has 5 notes.
                    if (size > MEMBER_SAMPLE) {
                        assertEquals(size - MEMBER_SAMPLE, c["moreMembers"]!!.jsonPrimitive.content.toInt())
                    }
                }
                g.close()
            }
        }
    }

    // A third test comparing payload BYTES was removed: on a small fixture the titles and summaries
    // dominate the payload, so it passed or failed on fixture size rather than on behaviour. The cap
    // itself is asserted above, and the ratio arithmetic is asserted on synthetic data below —
    // between them the property is covered without a metric that measures the wrong thing.

    @Test
    fun `graph_community returns the full membership for one theme`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seedWideVault()
                val g = graphFor(fx)
                g.rebuild(); awaitBuild(g)
                val tools = SvodTools(fx.engine, fx.index, fx.audit, RateLimiter.default(), fx.eventBus, graph = g)

                val first = tools.graphCommunities(fx.read, null, null, 5)
                    .data["communities"]!!.jsonArray.first().jsonObject
                val id = first["id"]!!.jsonPrimitive.content
                val size = first["size"]!!.jsonPrimitive.content.toInt()

                val one = tools.graphCommunity(fx.read, id)
                assertEquals("ok", one.status)
                val members = one.data["members"]!!.jsonArray
                // The whole point of the split: the targeted call is complete where the listing is not.
                assertEquals(size, members.size, "graph_community must return every member")
                assertEquals(id, one.data["id"]!!.jsonPrimitive.content)
                g.close()
            }
        }
    }

    @Test
    fun `an unknown community id is a clean not_found`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seedWideVault()
                val g = graphFor(fx)
                g.rebuild(); awaitBuild(g)
                val tools = SvodTools(fx.engine, fx.index, fx.audit, RateLimiter.default(), fx.eventBus, graph = g)

                assertEquals("not_found", tools.graphCommunity(fx.read, "L9-999").status)
                g.close()
            }
        }
    }

    @Test
    fun `a vault without a graph tells the agent it is not built, not that it is empty`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seedWideVault()
                // No GraphService at all — the tools stay registered so capability probes are stable.
                val res = fx.tools.graphCommunities(fx.read, null, null, 20)
                assertEquals("NOT_BUILT", res.data["state"]!!.jsonPrimitive.content)
                assertTrue(res.data["communities"]!!.jsonArray.isEmpty())
                // Without this an agent reads an empty list as "this vault has no themes" and reports
                // a conclusion about the user's notes that it has no basis for.
                val hint = res.data["hint"]!!.jsonPrimitive.content
                assertTrue("not been built" in hint, "the empty result must explain itself: $hint")
                assertTrue(fx.tools.graphCommunity(fx.read, "L0-0").status == "not_found")
            }
        }
    }

    @Test
    fun `the listing reports how many levels exist so a finer one can be requested`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seedWideVault()
                val g = graphFor(fx)
                g.rebuild(); awaitBuild(g)
                val tools = SvodTools(fx.engine, fx.index, fx.audit, RateLimiter.default(), fx.eventBus, graph = g)

                val res = tools.graphCommunities(fx.read, null, null, 5).data
                val levelCount = res["levelCount"]!!.jsonPrimitive.content.toInt()
                assertTrue(levelCount >= 1, "levelCount must be reported")
                // Measured on the real vault: level 0 ranks a specific query noticeably better than the
                // coarse default, so an agent has to be able to see that finer levels exist at all.
                assertTrue(res.containsKey("level"), "the served level must be stated")
                g.close()
            }
        }
    }

    /** Guards the KDoc claim that a summary-only payload is a small fraction of a full one. */
    @Test
    fun `sample size is small enough to keep a listing map-sized`() {
        val levels = listOf(
            CommunityLevel(0, (1..20).map { i ->
                Community("L0-$i", 0, (1..150).map { "long/path/to/note-$it-in-community-$i.md" }, title = "T$i")
            }),
        )
        val full = levels.single().communities.sumOf { c -> c.members.sumOf { it.length } }
        val preview = levels.single().communities.sumOf { c -> c.members.take(MEMBER_SAMPLE).sumOf { it.length } }
        assertTrue(
            preview * 20 < full,
            "previewing $MEMBER_SAMPLE of 150 must cut the member payload by more than 20x (was $preview vs $full)",
        )
    }
}
