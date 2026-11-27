package dev.svod.engine.mcp

import dev.svod.engine.core.Author
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.index.MarkdownChunker
import dev.svod.engine.index.SearchFilters
import dev.svod.engine.index.SearchMode
import dev.svod.engine.index.SearchQuery
import dev.svod.engine.security.PersonalDataScanner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpRestoreRevisionTest {

    private fun ToolResult.str(key: String): String? = data[key]?.jsonPrimitive?.content

    @Test
    fun `scribe restores an earlier commit, audited and reindexed`(): Unit = runBlocking {
        McpFixture().use { fx ->
            val v1 = fx.tools.write(fx.write, "notes/r.md", "# R\nthe heron nests by the river\n", null)
            assertEquals("ok", v1.status)
            val v1Commit = v1.str("commit")!!
            val v2 = fx.tools.write(fx.write, "notes/r.md", "# R\nnothing about birds now\n", v1.str("revision"))
            assertEquals("ok", v2.status)
            fx.index.waitIdle()
            fun hits() = fx.index.search(SearchQuery("heron", SearchFilters(), SearchMode.KEYWORD, 10)).hits.map { it.path }
            assertTrue("notes/r.md" !in hits(), "v2 no longer mentions the heron")

            val r = fx.tools.restoreRevision(fx.write, "notes/r.md", v1Commit, v2.str("revision"))
            assertEquals("ok", r.status, r.data.toString())
            assertEquals("notes/r.md", r.str("path"))
            assertEquals(v1.str("revision"), r.str("revision"))
            val commit = r.str("commit")!!
            assertEquals("# R\nthe heron nests by the river\n", fx.tools.read(fx.write, "notes/r.md").str("content"))
            val head = fx.engine.history("notes/r.md", 1).single()
            assertEquals(commit, head.commit)
            assertTrue(head.message.startsWith("restore: notes/r.md to $v1Commit"), head.message)
            assertEquals("Scribe", head.authorName)

            assertTrue(fx.audit.entries().any {
                it.agentId == "scribe" && it.tool == "restore_revision" && it.outcome == "ok" && it.detail?.contains(commit) == true
            }, "audited with the commit")

            fx.index.waitIdle()
            assertTrue("notes/r.md" in hits(), "reindexed after the restore")
        }
    }

    @Test
    fun `stale expectedRevision conflicts, bad revision is bad_request, absent note is not_found`(): Unit = runBlocking {
        McpFixture().use { fx ->
            val other = fx.tools.write(fx.write, "other.md", "x\n", null)
            val v1 = fx.tools.write(fx.write, "c.md", "one\n", null)
            fx.tools.write(fx.write, "c.md", "two\n", v1.str("revision"))

            val stale = fx.tools.restoreRevision(fx.write, "c.md", v1.str("commit")!!, v1.str("revision"))
            assertEquals("conflict", stale.status)
            assertEquals("two\n", stale.str("currentContent"))
            assertEquals("two\n", fx.tools.read(fx.write, "c.md").str("content"))
            assertTrue(fx.audit.entries().any { it.tool == "restore_revision" && it.outcome == "conflict" })

            assertEquals("bad_request", fx.tools.restoreRevision(fx.write, "c.md", "no-such-ref", null).status)
            assertEquals("bad_request", fx.tools.restoreRevision(fx.write, "c.md", "0".repeat(40), null).status)
            assertEquals("not_found", fx.tools.restoreRevision(fx.write, "c.md", other.str("commit")!!, null).status)

            // without expectedRevision the tool pins to the revision it just read
            assertEquals("ok", fx.tools.restoreRevision(fx.write, "c.md", v1.str("commit")!!, null).status)
            assertEquals("one\n", fx.tools.read(fx.write, "c.md").str("content"))
        }
    }

    @Test
    fun `a read-only agent is denied and nothing changes`(): Unit = runBlocking {
        McpFixture().use { fx ->
            val v1 = fx.tools.write(fx.write, "d.md", "one\n", null)
            val v2 = fx.tools.write(fx.write, "d.md", "two\n", v1.str("revision"))
            val r = fx.tools.restoreRevision(fx.read, "d.md", v1.str("commit")!!, v2.str("revision"))
            assertEquals("denied", r.status)
            assertTrue(r.isError)
            assertEquals("two\n", fx.tools.read(fx.write, "d.md").str("content"))
        }
    }

    @Test
    fun `restoring a version with personal data goes through the review`(): Unit = runBlocking {
        McpFixture().use { fx ->
            val tools = SvodTools(
                fx.engine, fx.index, fx.audit, RateLimiter.default(), fx.eventBus,
                requireReview = false, personalDataScanner = PersonalDataScanner(true),
            )
            val person = Author("person", "person@svod.test")
            val email = "jane.doe@example.org"
            val v1 = assertIs<WriteOutcome.Success>(fx.engine.write("p.md", "# Contact\nMail $email.\n", null, person))
            val v2 = assertIs<WriteOutcome.Success>(fx.engine.write("p.md", "# Contact\nremoved\n", v1.revision, person))

            val r = tools.restoreRevision(fx.write, "p.md", v1.commit, v2.revision)
            assertEquals("ok", r.status, r.data.toString())
            assertEquals("provisional", r.str("memoryStatus"))
            val text = fx.engine.read("p.md")!!.text
            assertEquals("provisional", MarkdownChunker.parse(text).status)
            assertTrue("Mail $email." in text)
            assertTrue(fx.engine.history("p.md", 1).single().message.startsWith("restore: p.md to ${v1.commit}"))
        }
    }
}
