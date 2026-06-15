package dev.svod.engine.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkGraphTest {

    @Test
    fun `alias and trailing slash are stripped from the target and the link resolves`() {
        val g = LinkGraph.build(
            mapOf(
                "index.md" to "see [[projects/actio-mobile/|actio-mobile]] and [[notes/b|B]]",
                "projects/actio-mobile.md" to "# Actio",
                "notes/b.md" to "# B",
            )
        )
        val targets = g.outlinks("index.md").map { it.target }
        // alias dropped, trailing slash normalized — NOT "projects/actio-mobile/|actio-mobile"
        assertEquals(listOf("projects/actio-mobile", "notes/b"), targets)
        // and the cleaned targets actually resolve (the trailing slash previously broke this)
        assertEquals("projects/actio-mobile.md", g.outlinks("index.md").first().resolvedPath)
        assertTrue(g.unresolved("index.md").isEmpty(), "both links resolve")
        assertTrue(g.backlinks("projects/actio-mobile.md").contains("index.md"), "backlink is recorded")
    }

    @Test
    fun `a heading suffix is preserved on the target but ignored for resolution`() {
        val g = LinkGraph.build(mapOf("a.md" to "[[b#section]]", "b.md" to "# B"))
        assertEquals("b#section", g.outlinks("a.md").first().target)
        assertEquals("b.md", g.outlinks("a.md").first().resolvedPath)
    }

    @Test
    fun `an escaped pipe is kept as part of the target`() {
        assertEquals("a|b", WikiLinks.clean("a\\|b"))
        assertEquals("x", WikiLinks.clean("x|alias"))
        assertEquals("x", WikiLinks.clean("x/"))
    }
}
