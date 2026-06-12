package dev.svod.engine.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FederatedLinkGraphTest {

    @Test
    fun `qualified cross-vault link resolves and surfaces as a backlink in the other vault`() {
        val g = FederatedLinkGraph.build(mapOf(
            "personal" to mapOf("note.md" to "see [[work:project]] for context"),
            "work" to mapOf("project.md" to "# Project"),
        ))

        val out = g.outlinks("personal", "note.md")
        assertEquals(1, out.size)
        assertEquals("work:project.md", out.single().resolvedGlobalId, "qualified link resolves into the work vault")

        assertTrue(g.backlinks("work", "project.md").contains("personal:note.md"),
            "the work note's backlinks include the cross-vault source")
    }

    @Test
    fun `unqualified link never crosses vaults`() {
        // both vaults have a note called "shared"; an unqualified [[shared]] must stay local
        val g = FederatedLinkGraph.build(mapOf(
            "personal" to mapOf("a.md" to "[[shared]]"),
            "work" to mapOf("shared.md" to "# Shared (work)"),
        ))
        // personal has no "shared" note, so the local unqualified link is unresolved — NOT matched to work's
        assertEquals(listOf("shared"), g.unresolved("personal", "a.md"))
        assertTrue(g.backlinks("work", "shared.md").isEmpty(), "work's note is not linked from personal via an unqualified link")
    }

    @Test
    fun `unqualified link resolves within its own vault`() {
        val g = FederatedLinkGraph.build(mapOf(
            "work" to mapOf("a.md" to "[[b]]", "b.md" to "# B"),
        ))
        assertEquals("work:b.md", g.outlinks("work", "a.md").single().resolvedGlobalId)
        assertTrue(g.backlinks("work", "b.md").contains("work:a.md"))
    }
}
