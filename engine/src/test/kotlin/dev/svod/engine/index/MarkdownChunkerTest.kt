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
    fun `comma-separated tag string is supported`() {
        val doc = MarkdownChunker.parse("---\ntags: alpha, beta, gamma\n---\nbody")
        assertEquals(listOf("alpha", "beta", "gamma"), doc.tags)
    }

    @Test
    fun `malformed frontmatter does not break parsing`() {
        val doc = MarkdownChunker.parse("---\n: : : not yaml : :\n---\n# H\nbody")
        assertEquals(listOf("H"), doc.chunks.map { it.heading })
    }
}
