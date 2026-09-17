package dev.svod.engine.memory

import dev.svod.engine.index.MarkdownChunker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [patchFrontmatter] patches single-line scalars in place and refuses anything it cannot patch unambiguously. */
class FrontmatterPatchTest {

    private val set = mapOf("status" to "active", "reviewed_by" to "O'Brien: Мария")

    @Test
    fun `patches in place, appends missing keys, removes keys and keeps CRLF`() {
        val raw = "---\r\ntype: fact\r\n# c\r\nstatus: provisional\r\nneeds-review: true\r\ncreated: 2026-09-01\r\n---\r\nBody\r\n"
        val out = patchFrontmatter(raw, set, setOf("needs-review"))
        assertEquals("---\r\ntype: fact\r\n# c\r\nstatus: active\r\ncreated: 2026-09-01\r\nreviewed_by: 'O''Brien: Мария'\r\n---\r\nBody\r\n", out)
        assertEquals("O'Brien: Мария", MarkdownChunker.parse(out!!).frontmatter["reviewed_by"])
    }

    @Test
    fun `refuses non-scalar, multi-line, repeated and missing frontmatter`() {
        assertNull(patchFrontmatter("---\nstatus:\n  - provisional\n---\nB", set, emptySet()), "block list")
        assertNull(patchFrontmatter("---\nstatus: [provisional]\n---\nB", set, emptySet()), "flow list")
        assertNull(patchFrontmatter("---\nstatus: |\n  provisional\n---\nB", set, emptySet()), "block scalar")
        assertNull(patchFrontmatter("---\nstatus: a\nstatus: b\n---\nB", set, emptySet()), "repeated key")
        assertNull(patchFrontmatter("No frontmatter", set, emptySet()), "no block")
    }
}
