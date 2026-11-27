package dev.svod.engine.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentChangesToolTest {

    private fun ToolResult.str(key: String): String? = data[key]?.jsonPrimitive?.content

    @Test
    fun `returns head, changes with paths, and a cursor`() {
        runBlocking {
            McpFixture(vaultId = "work").use { fx ->
                val w1 = fx.tools.write(fx.write, "a.md", "# A\nalpha body that stays", expectedRevision = null)
                fx.tools.move(fx.write, "a.md", "b.md", w1.str("revision"))
                val head = fx.engine.changes(null, null, 1).head

                val r = fx.tools.recentChanges(fx.read, since = null, before = null, max = 1)
                assertEquals("ok", r.status)
                assertFalse(r.isError)
                assertEquals(head, r.str("head"))
                assertEquals("work", r.str("vault"))
                val changes = r.data["changes"]!!.jsonArray
                assertEquals(1, changes.size)
                val top = changes.single().jsonObject
                assertEquals(head, top["commit"]!!.jsonPrimitive.content)
                assertEquals("Scribe", top["author"]!!.jsonPrimitive.content)
                // an agent not bound to a person commits as itself, without a Svod-Agent trailer: no "agent" key
                assertNull(top["agent"])
                assertEquals(1, top["pathCount"]!!.jsonPrimitive.int)
                val p = top["paths"]!!.jsonArray.single().jsonObject
                assertEquals("b.md", p["path"]!!.jsonPrimitive.content)
                assertEquals("rename", p["kind"]!!.jsonPrimitive.content)
                assertEquals("a.md", p["oldPath"]!!.jsonPrimitive.content)
                assertEquals(head, r.str("nextCursor"))

                val older = fx.tools.recentChanges(fx.read, since = null, before = r.str("nextCursor"), max = 1)
                val add = older.data["changes"]!!.jsonArray.single().jsonObject["paths"]!!.jsonArray.single().jsonObject
                assertEquals("add", add["kind"]!!.jsonPrimitive.content)
                assertNull(add["oldPath"])

                val nothingNew = fx.tools.recentChanges(fx.read, since = head, before = null, max = 50)
                assertEquals("ok", nothingNew.status)
                assertTrue(nothingNew.data["changes"]!!.jsonArray.isEmpty())
            }
        }
    }

    @Test
    fun `a bad since or before is bad_request, not an exception`() {
        runBlocking {
            McpFixture().use { fx ->
                fx.tools.write(fx.write, "a.md", "a", expectedRevision = null)
                val bad = fx.tools.recentChanges(fx.read, since = "yesterday-ish", before = null, max = 10)
                assertEquals("bad_request", bad.status)
                assertTrue(bad.isError)
                val badBefore = fx.tools.recentChanges(fx.read, since = null, before = "no-such-rev", max = 10)
                assertEquals("bad_request", badBefore.status)
            }
        }
    }

    @Test
    fun `is rate-limited like every tool`() {
        runBlocking {
            McpFixture(rateLimiter = RateLimiter(capacity = 2.0, refillPerSecond = 0.0)).use { fx ->
                assertEquals("ok", fx.tools.recentChanges(fx.read, null, null, 5).status)
                assertEquals("ok", fx.tools.recentChanges(fx.read, null, null, 5).status)
                assertEquals("rate_limited", fx.tools.recentChanges(fx.read, null, null, 5).status)
            }
        }
    }
}
