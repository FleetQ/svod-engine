package dev.svod.engine.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.eclipse.jgit.api.Git
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpBlameToolTest {

    private fun headCommit(fx: McpFixture): String =
        Git.open(fx.root.toFile()).use { g -> g.log().setMaxCount(1).call().single().name }

    private fun JsonObject.s(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `blame attributes each line to the commit that last changed it`() {
        runBlocking {
            McpFixture().use { fx ->
                val w1 = fx.tools.write(fx.write, "doc.md", "L1\nL2\nL3\n", expectedRevision = null)
                val c1 = headCommit(fx)
                fx.tools.write(fx.write, "doc.md", "L1\nL2 changed\nL3\n", w1.data["revision"]!!.jsonPrimitive.content)
                val c2 = headCommit(fx)
                assertNotEquals(c1, c2)

                val r = fx.tools.blame(fx.read, "doc.md")
                assertEquals("ok", r.status)
                assertEquals("doc.md", r.data["path"]!!.jsonPrimitive.content)
                val lines = r.data["lines"]!!.jsonArray.map { it.jsonObject }
                assertEquals(3, lines.size)
                assertEquals(listOf(1, 2, 3), lines.map { it["line"]!!.jsonPrimitive.int })
                assertEquals(listOf("L1", "L2 changed", "L3"), lines.map { it.s("text") })
                assertEquals(listOf(c1, c2, c1), lines.map { it.s("commit") })
                lines.forEach {
                    assertEquals("Scribe", it.s("author"))
                    assertEquals("scribe@agents.test", it.s("email"))
                    assertTrue(it["epochSeconds"]!!.jsonPrimitive.long > 0)
                    assertNull(it["agent"], "an unbound agent commits without a Svod-Agent trailer")
                }
            }
        }
    }

    @Test
    fun `a bound agent's lines carry the Svod-Agent trailer`() {
        runBlocking {
            val bound = AgentRegistry.AgentSpec(
                "write-token", "scribe", AgentRole.WRITE,
                name = "Person P", email = "person@team.test", userId = "person",
            )
            McpFixture(agents = listOf(bound, READ_AGENT)).use { fx ->
                fx.tools.write(fx.write, "n.md", "only line", expectedRevision = null)
                val line = fx.tools.blame(fx.read, "n.md").data["lines"]!!.jsonArray.single().jsonObject
                assertEquals("Person P", line.s("author"))
                assertEquals("person@team.test", line.s("email"))
                assertEquals("scribe", line.s("agent"))
            }
        }
    }

    @Test
    fun `renames are not followed, a directory is not_found and CR is dropped`() {
        runBlocking {
            McpFixture().use { fx ->
                val w = fx.tools.write(fx.write, "dir/old.md", "a\r\nb\r\n", expectedRevision = null)
                fx.tools.move(fx.write, "dir/old.md", "dir/new.md", w.data["revision"]!!.jsonPrimitive.content)
                val moved = headCommit(fx)
                val lines = fx.tools.blame(fx.read, "/dir/new.md").let {
                    assertEquals("dir/new.md", it.data["path"]!!.jsonPrimitive.content)
                    it.data["lines"]!!.jsonArray.map { l -> l.jsonObject }
                }
                assertEquals(listOf("a", "b"), lines.map { it.s("text") })
                assertEquals(listOf(moved, moved), lines.map { it.s("commit") })
                assertEquals("not_found", fx.tools.blame(fx.read, "dir").status)
            }
        }
    }

    @Test
    fun `blame of a missing note is not_found`() {
        runBlocking {
            McpFixture().use { fx ->
                fx.tools.write(fx.write, "other.md", "x", expectedRevision = null)
                val r = fx.tools.blame(fx.read, "nope.md")
                assertEquals("not_found", r.status)
            }
        }
    }
}
