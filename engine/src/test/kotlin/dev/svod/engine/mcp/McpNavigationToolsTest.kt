package dev.svod.engine.mcp

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

/** MCP `tree` and `grep` — see docs/test-plan-openviking-borrow.md (T*, G*). All calls use the read-only agent. */
class McpNavigationToolsTest {

    private fun ToolResult.folders(): Map<String, Int> =
        data["folders"]!!.jsonArray.associate { it.jsonObject["path"]!!.jsonPrimitive.content to it.jsonObject["files"]!!.jsonPrimitive.int }

    private fun ToolResult.hits(): List<JsonObject> = data["hits"]!!.jsonArray.map { it.jsonObject }
    private fun ToolResult.paths(): List<String> = hits().map { it["path"]!!.jsonPrimitive.content }
    private fun ToolResult.number(key: String): Int = data[key]!!.jsonPrimitive.int
    private fun ToolResult.flag(key: String): Boolean = data[key]!!.jsonPrimitive.boolean

    private suspend fun McpFixture.note(path: String, text: String) {
        engine.write(path, text, null, write.author)
    }

    private suspend fun McpFixture.grep(
        pattern: String,
        pathPrefix: String? = null,
        literal: Boolean = false,
        ignoreCase: Boolean = false,
        limit: Int = 50,
    ): ToolResult = tools.grep(read, pattern, pathPrefix, literal, ignoreCase, limit)

    @Test
    fun `tree counts files recursively per folder down to the depth`() = runBlocking {
        McpFixture().use { fx ->
            fx.note("a/x.md", "x")
            fx.note("a/b/y.md", "y")
            fx.note("a/b/c/z.md", "z")
            fx.note("r.md", "r")

            val shallow = fx.tools.tree(fx.read, null, 1)
            assertEquals("ok", shallow.status)
            assertEquals(mapOf("a/" to 3), shallow.folders())
            assertEquals(1, shallow.number("rootFiles"))
            assertEquals(4, shallow.number("totalFiles"))

            assertEquals(mapOf("a/" to 3, "a/b/" to 2), fx.tools.tree(fx.read, null, 2).folders())

            val scoped = fx.tools.tree(fx.read, "a/", 5)
            assertEquals(mapOf("a/b/" to 2, "a/b/c/" to 1), scoped.folders())
            assertEquals(1, scoped.number("rootFiles"), "a/x.md sits directly at the prefix")
            assertEquals(3, scoped.number("totalFiles"))
        }
    }

    @Test
    fun `grep finds literal text with its line and treats regex characters literally`() = runBlocking {
        McpFixture().use { fx ->
            fx.note("ops/engine.md", "# Engine\nlisten on 127.0.0.1:7619 by default\nother")
            fx.note("ops/decoy.md", "# Decoy\n127x0x0x1:7619 looks similar")

            val r = fx.grep("127.0.0.1:7619", literal = true)
            assertEquals("ok", r.status)
            val hits = r.hits()
            assertEquals(1, hits.size, hits.toString())
            assertEquals("ops/engine.md", hits[0]["path"]!!.jsonPrimitive.content)
            assertEquals(2, hits[0]["line"]!!.jsonPrimitive.int)
            assertEquals("listen on 127.0.0.1:7619 by default", hits[0]["text"]!!.jsonPrimitive.content)

            // As a regex '.' matches any character, so the decoy matches as well.
            assertEquals(2, fx.grep("127.0.0.1:7619").hits().size)
        }
    }

    @Test
    fun `grep regex honours ignoreCase and rejects an invalid pattern`() = runBlocking {
        McpFixture().use { fx ->
            fx.note("n.md", "Release V1.22.0 shipped")

            assertEquals(0, fx.grep("v1\\.\\d+\\.0").hits().size)
            assertEquals(1, fx.grep("v1\\.\\d+\\.0", ignoreCase = true).hits().size)

            val bad = fx.grep("(")
            assertEquals("bad_request", bad.status)
            assertTrue(bad.isError)
        }
    }

    @Test
    fun `grep matches each line on its own across LF, CRLF and CR breaks`() = runBlocking {
        McpFixture().use { fx ->
            fx.note("breaks.md", "first line\r\nsecond\rthird\n\nfifth")

            suspend fun lines(pattern: String) = fx.grep(pattern).hits().map { it["line"]!!.jsonPrimitive.int }
            assertEquals(listOf(2), lines("^second$"), "CRLF ends line 1, a lone CR ends line 2")
            assertEquals(listOf(3), lines("^third$"))
            assertEquals(listOf(5), lines("\\Afifth\\z"), "\\A and \\z anchor at the line, not the note")
            assertEquals(listOf(4), lines("^$"), "the empty line between LF LF")
            assertEquals(emptyList(), lines("d\\s+t"), "a match never spans a line break")
            assertEquals("second", fx.grep("^second$").hits()[0]["text"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `grep never returns private content and keeps line numbers true to the file`() = runBlocking {
        McpFixture().use { fx ->
            fx.note("p/spans.md", "# Spans\nline two\n<private>\nLEAK_SPAN_NEEDLE\n</private>\nPUBLIC_NEEDLE here")
            fx.note("p/whole.md", "---\nprivate: true\n---\n# Whole\nPUBLIC_NEEDLE and LEAK_WHOLE_NEEDLE")

            assertTrue(fx.grep("LEAK_", literal = true).hits().isEmpty(), "no private span, no private note")

            val pub = fx.grep("PUBLIC_NEEDLE", literal = true).hits()
            assertEquals(listOf("p/spans.md"), pub.map { it["path"]!!.jsonPrimitive.content }, "a private:true note is never searched")
            // Line 6 in the raw file. Stripping the three-line span instead of masking it would report 4,
            // and an agent editing by that line number would hit the wrong line.
            assertEquals(6, pub[0]["line"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `grep treats an unclosed private tag as private to the end of the note`() = runBlocking {
        McpFixture().use { fx ->
            fx.note("p/typo.md", "# Typo\nPUBLIC_NEEDLE\n<private>\nLEAK_UNCLOSED_NEEDLE\nstill private")

            assertTrue(fx.grep("LEAK_UNCLOSED_NEEDLE", literal = true).hits().isEmpty(), "a missing </private> must fail closed")
            assertEquals(2, fx.grep("PUBLIC_NEEDLE", literal = true).hits().single()["line"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `grep never searches captured sessions and reaches messy drafts only by prefix`() = runBlocking {
        McpFixture().use { fx ->
            fx.note("messy/sessions/1700-proj-abcd1234.md", "---\ntype: session\nsessionId: abcd1234\n---\nMESSY_NEEDLE in a transcript")
            fx.note("messy/draft.md", "# Draft\nMESSY_NEEDLE in a draft")
            fx.note("notes/real.md", "# Real\nMESSY_NEEDLE in a real note")

            assertEquals(listOf("notes/real.md"), fx.grep("MESSY_NEEDLE").paths(), "default: no messy/ at all")
            assertEquals(listOf("messy/draft.md"), fx.grep("MESSY_NEEDLE", pathPrefix = "messy/").paths(), "drafts by explicit prefix, sessions still not")
            assertTrue(fx.grep("MESSY_NEEDLE", pathPrefix = "messy/sessions/").hits().isEmpty(), "no prefix escape into sessions")
        }
    }

    @Test
    fun `grep stops at the limit and says so`() = runBlocking {
        McpFixture().use { fx ->
            fx.note("l.md", (1..5).joinToString("\n") { "HIT $it" })

            val cut = fx.grep("HIT", literal = true, limit = 2)
            assertEquals(2, cut.hits().size)
            assertTrue(cut.flag("truncated"))

            val all = fx.grep("HIT", literal = true)
            assertEquals(5, all.hits().size)
            assertEquals(false, all.flag("truncated"))
        }
    }

    @Test
    fun `a catastrophic regex is cut off by the time budget`() = runBlocking {
        McpFixture().use { fx ->
            fx.note("redos.md", "a".repeat(40) + "!")

            // Measured on JDK 20 with a plain String: the textbook `(a+)+$` is optimised away (0 ms at any
            // length), but a backreference defeats that — `^(a+)+\1$` takes 0.2 s at 24 chars and 3.1 s at
            // 28, ~15x per 4 more. At 40 it would run for hours, so this finishes under 10 s only if the
            // budget cut it off.
            val started = System.nanoTime()
            val r = fx.grep("^(a+)+\\1$")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertEquals("ok", r.status)
            assertTrue(r.flag("timedOut"), "the budget, not the match, ended the call")
            assertTrue(elapsedMs < 10_000, "took $elapsedMs ms")
        }
    }

    @Test
    fun `a regex that overflows the stack on a long line is reported, not thrown`() = runBlocking {
        McpFixture().use { fx ->
            // java.util.regex recurses once per repetition of a group with alternation, so `(a|b)*c` over a
            // long line overflows the stack. Real vaults have lines over 5,000 chars (review measurement).
            fx.note("long.md", "ab".repeat(20_000) + "\nSHORT_NEEDLE c")

            val r = fx.grep("(a|b)*c")

            assertEquals("ok", r.status)
            assertTrue(r.number("unsearchableLines") >= 1, r.data.toString())
            assertEquals(2, r.hits().single()["line"]!!.jsonPrimitive.int, "the other lines are still searched")
        }
    }
}
