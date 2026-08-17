package dev.svod.engine.mcp

import dev.svod.engine.index.SearchQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ниво 1: `context_pack(graphExpand = true)` pulls the 1-hop wikilink neighbourhood of the strongest
 * hits into whatever token budget is left over.
 *
 * The contract being defended is that expansion is a **bonus, never a cost**: the ranked blocks a
 * caller would have received without it must be present, unchanged, and first. Anything else would
 * make an agent's recall worse in exchange for context it did not ask for.
 *
 * NB (`mem:kotlin-junit-silent-skip`): block bodies only, so JUnit collects these.
 */
class ContextPackGraphExpandTest {

    private fun blocks(result: ToolResult): List<JsonObject> =
        (result.data["blocks"]?.jsonArray ?: error("no blocks in $result")).map { it.jsonObject }

    private fun paths(result: ToolResult): List<String> =
        blocks(result).map { it["path"]!!.jsonPrimitive.content }

    /** `hub` is the search target; `sat*` are reachable only through wikilinks, never by the query. */
    private suspend fun McpFixture.seedLinkedCluster() {
        tools.write(write, "hub.md", "# Hub\nzebra quokka narwhal topic\n[[sat-a.md]] [[sat-b.md]]", null)
        tools.write(write, "sat-a.md", "# Satellite A\ncompletely unrelated wording about gardening", null)
        tools.write(write, "sat-b.md", "# Satellite B\nanother unrelated note about bicycles", null)
        tools.write(write, "backlinker.md", "# Backlinker\npoints at the hub [[hub.md]]", null)
        tools.write(write, "noise.md", "# Noise\nnothing to do with anything here", null)
        index.reconcileNow()
    }

    @Test
    fun `expansion adds linked neighbours that the query itself never matched`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seedLinkedCluster()
                val q = SearchQuery(text = "zebra quokka narwhal", limit = 50)

                val plain = paths(fx.tools.contextPack(fx.read, q, tokenBudget = 4000, graphExpand = false))
                val expanded = paths(fx.tools.contextPack(fx.read, q, tokenBudget = 4000, graphExpand = true))

                assertTrue("hub.md" in plain, "the query must find the hub on its own")
                assertTrue(
                    plain.none { it == "sat-a.md" || it == "sat-b.md" },
                    "satellites share no words with the query; if they appear without expansion the " +
                        "fixture no longer tests what it claims: $plain",
                )
                assertTrue("sat-a.md" in expanded && "sat-b.md" in expanded, "outlinks must be pulled in: $expanded")
                assertTrue("backlinker.md" in expanded, "backlinks must be pulled in too: $expanded")
            }
        }
    }

    @Test
    fun `expanded blocks are marked and name the hit that pulled them in`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seedLinkedCluster()
                val result = fx.tools.contextPack(
                    fx.read, SearchQuery(text = "zebra quokka narwhal", limit = 50),
                    tokenBudget = 4000, graphExpand = true,
                )
                val expanded = blocks(result).filter { it["viaGraph"]?.jsonPrimitive?.boolean == true }
                assertTrue(expanded.isNotEmpty(), "expansion produced nothing to mark")
                for (b in expanded) {
                    assertEquals(
                        "hub.md", b["viaPath"]!!.jsonPrimitive.content,
                        "an expanded block must attribute itself to the ranked hit it came from",
                    )
                }
                // Primary evidence must stay distinguishable from context, or an agent cannot weigh it.
                val primary = blocks(result).filter { it["viaGraph"] == null }
                assertTrue(primary.isNotEmpty(), "the ranked hits must not all be relabelled as expanded")
            }
        }
    }

    @Test
    fun `expansion never drops or reorders the ranked hits`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seedLinkedCluster()
                val q = SearchQuery(text = "zebra quokka narwhal", limit = 50)
                val plain = paths(fx.tools.contextPack(fx.read, q, tokenBudget = 4000, graphExpand = false))
                val expanded = paths(fx.tools.contextPack(fx.read, q, tokenBudget = 4000, graphExpand = true))

                assertEquals(plain, expanded.take(plain.size), "ranked blocks must be preserved, in order, first")
                assertTrue(expanded.size > plain.size, "expansion should have added something here")
            }
        }
    }

    @Test
    fun `expansion never duplicates a note already packed`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seedLinkedCluster()
                // A query that matches BOTH the hub and a note the hub links to, so the neighbour is
                // already packed by the time expansion runs.
                val result = fx.tools.contextPack(
                    fx.read, SearchQuery(text = "hub satellite gardening bicycles", limit = 50),
                    tokenBudget = 8000, graphExpand = true,
                )
                val all = paths(result)
                assertEquals(all.size, all.toSet().size, "a note must appear at most once: $all")
            }
        }
    }

    @Test
    fun `expansion respects the token budget and keeps the pack honest`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seedLinkedCluster()
                val result = fx.tools.contextPack(
                    fx.read, SearchQuery(text = "zebra quokka narwhal", limit = 50),
                    // Tight enough that the neighbourhood cannot all fit.
                    tokenBudget = 40, graphExpand = true,
                )
                val reported = result.data["estimatedTokens"]!!.jsonPrimitive.int
                val budget = result.data["tokenBudget"]!!.jsonPrimitive.int
                val summed = blocks(result).sumOf { it["tokens"]!!.jsonPrimitive.int }
                assertEquals(summed, reported, "estimatedTokens must equal the blocks actually returned")
                assertTrue(
                    blocks(result).count { it["viaGraph"]?.jsonPrimitive?.boolean == true } == 0 || reported <= budget,
                    "expansion must never push the pack over its budget (got $reported > $budget)",
                )
            }
        }
    }

    @Test
    fun `graphExpand off leaves the pack byte-identical to before the feature`() {
        McpFixture().use { fx ->
            runBlocking {
                fx.seedLinkedCluster()
                val q = SearchQuery(text = "zebra quokka narwhal", limit = 50)
                val off = fx.tools.contextPack(fx.read, q, tokenBudget = 4000, graphExpand = false)
                // The default must equal the explicit off — a caller that never heard of this feature
                // must not have its behaviour changed by it.
                val default = fx.tools.contextPack(fx.read, q, tokenBudget = 4000)
                assertEquals(off.data.toString(), default.data.toString())
                assertTrue(
                    blocks(off).none { it.containsKey("viaGraph") },
                    "no expansion marker may appear when expansion is off",
                )
            }
        }
    }
}
