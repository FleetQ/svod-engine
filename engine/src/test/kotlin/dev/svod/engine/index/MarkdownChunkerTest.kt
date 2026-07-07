package dev.svod.engine.index

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownChunkerTest {

    @Test
    fun `parses frontmatter, tags, dates and heading-level chunks`() {
        val doc = MarkdownChunker.parse(
            """
            ---
            title: Test Note
            tags: [alpha, beta]
            created: 2024-05-01
            ---
            intro paragraph
            # First
            content of first
            ## Sub
            nested content
            # Second
            content of second
            """.trimIndent()
        )

        assertEquals("Test Note", doc.title)
        assertEquals(listOf("alpha", "beta"), doc.tags)
        assertEquals(LocalDate.parse("2024-05-01").atStartOfDay(ZoneOffset.UTC).toEpochSecond(), doc.created)

        // preamble + First + Sub + Second
        assertEquals(4, doc.chunks.size)
        assertEquals(listOf("", "First", "Sub", "Second"), doc.chunks.map { it.heading })
        assertTrue(doc.chunks[0].text.contains("intro paragraph"))
    }

    @Test
    fun `unchanged section keeps a stable content hash`() {
        val v1 = MarkdownChunker.parse("# A\nalpha\n# B\nbeta")
        val v2 = MarkdownChunker.parse("# A\nalpha\n# B\nBETA CHANGED")
        // A is byte-identical across versions; B changed
        assertEquals(v1.chunks[0].contentHash, v2.chunks[0].contentHash)
        assertTrue(v1.chunks[1].contentHash != v2.chunks[1].contentHash)
    }

    @Test
    fun `an oversized section is split into budget-sized chunks without losing content`() {
        val big = "word ".repeat(1000) // ~5000 chars, well over the per-chunk budget
        val doc = MarkdownChunker.parse("# Big\n$big")
        assertTrue(doc.chunks.size > 1, "an oversized section splits into multiple chunks")
        assertTrue(doc.chunks.all { it.heading == "Big" }, "each sub-chunk keeps the section heading")
        assertTrue(doc.chunks.all { it.text.length <= 2000 }, "each chunk stays within the char budget")
        val joined = doc.chunks.joinToString(" ") { it.text }
        assertEquals(1000, Regex("\\bword\\b").findAll(joined).count(), "no words dropped across the split")
    }

    @Test
    fun `a small section is not split`() {
        assertEquals(1, MarkdownChunker.parse("# H\nshort body").chunks.size)
    }

    @Test
    fun `a single token longer than the budget is hard-cut, not dropped`() {
        val giant = "x".repeat(5000) // e.g. a base64 blob or URL with no whitespace
        val doc = MarkdownChunker.parse("# G\n$giant")
        assertTrue(doc.chunks.size > 1)
        assertTrue(doc.chunks.all { it.text.length <= 2000 })
        assertEquals(5000, doc.chunks.sumOf { it.text.count { ch -> ch == 'x' } }, "every char preserved")
    }

    @Test
    fun `comma-separated tag string is supported`() {
        val doc = MarkdownChunker.parse("---\ntags: alpha, beta, gamma\n---\nbody")
        assertEquals(listOf("alpha", "beta", "gamma"), doc.tags)
    }

    @Test
    fun `malformed frontmatter does not break parsing`() {
        val doc = MarkdownChunker.parse("---\n: : : not yaml : :\n---\n# H\nbody")
        assertEquals(listOf("H"), doc.chunks.map { it.heading })
    }

    @Test
    fun `private frontmatter yields no chunks but keeps the raw body intact`() {
        val raw = "---\nprivate: true\n---\n# Secret\ntop secret body"
        val doc = MarkdownChunker.parse(raw)
        assertTrue(doc.private, "private:true is detected")
        assertEquals(emptyList(), doc.chunks, "a private note produces no indexable chunks")
        assertTrue(doc.body.contains("top secret body"), "raw body is preserved (source of truth untouched)")
    }

    @Test
    fun `private spans are stripped from chunks but not from the raw body`() {
        val doc = MarkdownChunker.parse("# H\nvisible before <private>SECRET_SPAN</private> visible after")
        assertTrue(doc.chunks.none { it.text.contains("SECRET_SPAN") }, "span content never enters a chunk")
        assertTrue(doc.chunks.any { it.text.contains("visible before") && it.text.contains("visible after") }, "surrounding text stays")
        assertTrue(doc.body.contains("SECRET_SPAN"), "the raw body keeps the span (committed bytes unchanged)")
    }

    @Test
    fun `stripPrivateSpans removes multiline and multiple spans, case-insensitively`() {
        val stripped = MarkdownChunker.stripPrivateSpans("a<private>x\ny</private>b<PRIVATE>z</PRIVATE>c")
        assertEquals("abc", stripped)
    }
}
