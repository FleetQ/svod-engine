package dev.svod.engine.mcp

import dev.svod.engine.index.SearchMode
import dev.svod.engine.index.SearchQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpToolsTest {

    private fun ToolResult.str(key: String): String? = data[key]?.jsonPrimitive?.content

    @Test
    fun `context_pack assembles a budgeted, deduped, cited context block`() = runBlocking {
        McpFixture().use { fx ->
            fx.tools.write(fx.write, "a.md", "# A\nshared alpha topic", expectedRevision = null)
            // two sections both matching ⇒ two search hits for ONE note ⇒ must dedup to one block
            fx.tools.write(fx.write, "d.md", "# D1\nshared one\n# D2\nshared two", expectedRevision = null)
            fx.index.waitIdle()

            val r = fx.tools.contextPack(fx.read, SearchQuery("shared", mode = SearchMode.KEYWORD, limit = 50), tokenBudget = 2000)
            assertFalse(r.isError)
            val blocks = r.data["blocks"]!!.jsonArray
            assertTrue(blocks.isNotEmpty(), "returns blocks")
            val paths = blocks.map { it.jsonObject["path"]!!.jsonPrimitive.content }
            assertEquals(paths.toSet().size, paths.size, "one block per note (d.md deduped despite two hits)")
            assertTrue(paths.contains("d.md"))
            // provenance: every block cites the latest commit + author that touched the note
            val first = blocks.first().jsonObject
            assertTrue(first["commit"]!!.jsonPrimitive.content.isNotEmpty(), "commit provenance")
            assertEquals("Scribe", first["author"]!!.jsonPrimitive.content, "author provenance")
            assertTrue(r.data["estimatedTokens"]!!.jsonPrimitive.int > 0)

            // a tiny budget still returns at least the single top block (never empty when hits exist)
            val tiny = fx.tools.contextPack(fx.read, SearchQuery("shared", mode = SearchMode.KEYWORD, limit = 50), tokenBudget = 1)
            assertEquals(1, tiny.data["blocks"]!!.jsonArray.size, "at least one block even under a tiny budget")
        }
    }

    @Test
    fun `write then read round-trips and attributes the commit to the agent`() = runBlocking {
        McpFixture().use { fx ->
            val w = fx.tools.write(fx.write, "notes/a.md", "# A\nhello", expectedRevision = null)
            assertEquals("ok", w.status)
            val rev = w.str("revision")!!

            val r = fx.tools.read(fx.write, "notes/a.md")
            assertEquals("ok", r.status)
            assertEquals("# A\nhello", r.str("content"))
            assertEquals(rev, r.str("revision"))

            // identity flows to git as the author
            assertEquals("Scribe", fx.engine.history("notes/a.md").first().authorName)
        }
    }

    @Test
    fun `edit replaces a unique substring without resending the note`() = runBlocking {
        McpFixture().use { fx ->
            fx.tools.write(fx.write, "e.md", "# Doc\nstatus: draft\nbody stays intact", expectedRevision = null)

            val r = fx.tools.edit(fx.write, "e.md", "status: draft", "status: final", replaceAll = false, expectedRevision = null)
            assertEquals("ok", r.status)
            assertEquals("# Doc\nstatus: final\nbody stays intact", fx.tools.read(fx.write, "e.md").str("content"))

            // absent needle → bad_request, note untouched
            val missing = fx.tools.edit(fx.write, "e.md", "no such text", "x", replaceAll = false, expectedRevision = null)
            assertEquals("bad_request", missing.status)
            assertTrue(missing.isError)

            // ambiguous needle → bad_request unless replaceAll
            fx.tools.write(fx.write, "amb.md", "aa bb aa", expectedRevision = null)
            val ambiguous = fx.tools.edit(fx.write, "amb.md", "aa", "cc", replaceAll = false, expectedRevision = null)
            assertEquals("bad_request", ambiguous.status)
            val all = fx.tools.edit(fx.write, "amb.md", "aa", "cc", replaceAll = true, expectedRevision = null)
            assertEquals("ok", all.status)
            assertEquals("cc bb cc", fx.tools.read(fx.write, "amb.md").str("content"))

            // read-only role is denied like every mutation
            assertEquals("denied", fx.tools.edit(fx.read, "e.md", "final", "draft", replaceAll = false, expectedRevision = null).status)

            // stale expectedRevision → standard conflict shape, content untouched
            val cur = fx.tools.read(fx.write, "e.md").str("revision")!!
            val stale = fx.tools.edit(fx.write, "e.md", "final", "draft", replaceAll = false, expectedRevision = "0".repeat(cur.length))
            assertEquals("conflict", stale.status)
            assertEquals("# Doc\nstatus: final\nbody stays intact", fx.tools.read(fx.write, "e.md").str("content"))
        }
    }

    @Test
    fun `read-only agent is denied mutations and nothing is written`() = runBlocking {
        McpFixture().use { fx ->
            val w = fx.tools.write(fx.read, "notes/x.md", "nope", expectedRevision = null)
            assertEquals("denied", w.status)
            assertTrue(w.isError)
            assertEquals("not_found", fx.tools.read(fx.read, "notes/x.md").status)
        }
    }

    @Test
    fun `stale write returns a conflict with current content, never overwrites`() = runBlocking {
        McpFixture().use { fx ->
            fx.tools.write(fx.write, "c.md", "v1", expectedRevision = null)
            val conflict = fx.tools.write(fx.write, "c.md", "v2", expectedRevision = null)
            assertEquals("conflict", conflict.status)
            assertEquals("v1", conflict.str("currentContent"))
            assertEquals("v1", fx.tools.read(fx.write, "c.md").str("content"))
        }
    }

    @Test
    fun `messy draft promotes into the vault and bad namespace is rejected`() = runBlocking {
        McpFixture().use { fx ->
            val draft = fx.tools.write(fx.write, "messy/draft.md", "rough notes", expectedRevision = null)
            val promoted = fx.tools.promote(fx.write, "messy/draft.md", "vault/clean.md", expectedRevision = draft.str("revision"))
            assertEquals("ok", promoted.status)
            assertEquals("not_found", fx.tools.read(fx.write, "messy/draft.md").status)
            assertEquals("rough notes", fx.tools.read(fx.write, "vault/clean.md").str("content"))

            // promoting something that isn't under messy/ is a bad request
            fx.tools.write(fx.write, "vault/other.md", "x", expectedRevision = null)
            val bad = fx.tools.promote(fx.write, "vault/other.md", "vault/dest.md", expectedRevision = null)
            assertEquals("bad_request", bad.status)
            assertTrue(bad.isError)
        }
    }

    @Test
    fun `delete move history diff get_revision search work`() = runBlocking {
        McpFixture().use { fx ->
            val v1 = fx.tools.write(fx.write, "doc.md", "# Doc\nfirst version about lucene", expectedRevision = null)
            val r1 = v1.str("revision")!!
            val c1 = v1.str("commit")!!
            val v2 = fx.tools.write(fx.write, "doc.md", "# Doc\nsecond version about lucene", expectedRevision = r1)
            val c2 = v2.str("commit")!!

            // history
            val hist = fx.tools.history(fx.write, "doc.md", 10)
            assertEquals("ok", hist.status)
            assertTrue(hist.data["commits"]!!.jsonArray.size >= 2)

            // diff between the two commits mentions both versions
            val diff = fx.tools.diff(fx.write, "doc.md", c1, c2)
            assertEquals("ok", diff.status)
            assertTrue(diff.str("diff")!!.contains("second version"))

            // get_revision pulls the old content back
            val old = fx.tools.getRevision(fx.write, "doc.md", c1)
            assertEquals("first version about lucene", old.str("content")!!.substringAfter("\n"))

            // search (BM25-only index) finds it
            fx.index.waitIdle()
            val search = fx.tools.search(fx.write, SearchQuery("lucene", mode = SearchMode.KEYWORD))
            assertTrue(search.data["hits"]!!.jsonArray.isNotEmpty())

            // move then confirm
            assertEquals("ok", fx.tools.move(fx.write, "doc.md", "archive/doc.md", expectedRevision = v2.str("revision")).status)
            assertEquals("not_found", fx.tools.read(fx.write, "doc.md").status)

            // delete
            val cur = fx.tools.read(fx.write, "archive/doc.md").str("revision")
            assertEquals("ok", fx.tools.delete(fx.write, "archive/doc.md", expectedRevision = cur).status)
        }
    }

    @Test
    fun `link and graph_query resolve wikilinks and backlinks`() = runBlocking {
        McpFixture().use { fx ->
            fx.tools.write(fx.write, "a.md", "# A\nlinks to [[b]] and [[missing]]", expectedRevision = null)
            fx.tools.write(fx.write, "b.md", "# B\nback to [[a]]", expectedRevision = null)

            val link = fx.tools.link(fx.write, "a.md")
            assertEquals("ok", link.status)
            val outTargets = link.data["outlinks"]!!.jsonArray.map { it.jsonObjectField("target") }
            assertTrue(outTargets.containsAll(listOf("b", "missing")))
            assertEquals(listOf("missing"), link.data["unresolved"]!!.jsonArray.map { it.jsonPrimitive.content })

            val graph = fx.tools.graphQuery(fx.write, "b.md")
            assertTrue(graph.data["backlinks"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("a.md"))
        }
    }

    @Test
    fun `every mutation is recorded in the audit log with the agent identity`() = runBlocking {
        McpFixture().use { fx ->
            fx.tools.write(fx.write, "audit-me.md", "content", expectedRevision = null)
            fx.tools.write(fx.read, "denied.md", "x", expectedRevision = null) // denied, still audited
            val entries = fx.audit.entries()
            assertTrue(entries.any { it.tool == "write" && it.agentId == "scribe" && it.outcome == "ok" && it.path == "audit-me.md" })
            assertTrue(entries.any { it.tool == "write" && it.agentId == "reader" && it.outcome == "denied" })
        }
    }

    @Test
    fun `rate limiting throttles an agent past its quota`() = runBlocking {
        McpFixture(rateLimiter = RateLimiter(capacity = 2.0, refillPerSecond = 0.0)).use { fx ->
            assertEquals("not_found", fx.tools.read(fx.write, "none.md").status) // consumes 1
            assertEquals("not_found", fx.tools.read(fx.write, "none.md").status) // consumes 2
            val limited = fx.tools.read(fx.write, "none.md")                       // bucket empty
            assertEquals("rate_limited", limited.status)
            assertTrue(limited.isError)
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectField(key: String): String? =
    (this as kotlinx.serialization.json.JsonObject)[key]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
