package dev.svod.engine.graph

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.VaultFixture
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** The incremental link/tag index must stay byte-for-byte equivalent to a full rebuild. */
class LinkIndexTest {

    private val A = Author("t", "t@x")

    @Test
    fun `incremental index matches a full rebuild across add, modify, rename and delete`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            e.write("a.md", "---\ntags: [x]\n---\n# A\n[[b]] and [[projects/c/|alias]]", null, A)
            e.write("b.md", "# B", null, A)
            e.write("projects/c.md", "# C", null, A)

            LinkIndex(fx.root).use { li ->
                assertMatches(li, e)

                // modify a's links (content-only change)
                e.write("a.md", "# A\n[[c]] now", e.read("a.md")!!.revision, A)
                assertMatches(li, e)

                // add a note — changes the path set (and resolves [[b]] from the new note)
                e.write("d.md", "---\ntags: [x, y]\n---\n# D\n[[b]]", null, A)
                assertMatches(li, e)

                // rename b.md -> notes/b.md (a previously-resolved backlink must follow)
                e.move("b.md", "notes/b.md", e.read("b.md")!!.revision, A)
                assertMatches(li, e)

                // delete projects/c.md (its inbound links become unresolved)
                e.delete("projects/c.md", e.read("projects/c.md")!!.revision, A)
                assertMatches(li, e)
            }
        }
    }

    private suspend fun assertMatches(li: LinkIndex, e: SvodEngine) {
        val notes = e.list().filter { it.endsWith(".md") }.associateWith { e.read(it)!!.text }
        val expected = LinkGraph.build(notes)
        val actual = li.graph()
        assertEquals(expected.nodePaths(), actual.nodePaths(), "node set")
        for (p in notes.keys) {
            assertEquals(
                expected.outlinks(p).map { it.target to it.resolvedPath }.toSet(),
                actual.outlinks(p).map { it.target to it.resolvedPath }.toSet(),
                "outlinks of $p",
            )
            assertEquals(expected.backlinks(p).toSet(), actual.backlinks(p).toSet(), "backlinks of $p")
        }
        // tag counts match the full parse
        val expectedTags = HashMap<String, Int>()
        for (c in notes.values) dev.svod.engine.index.MarkdownChunker.parse(c).tags.forEach { expectedTags.merge(it, 1, Int::plus) }
        assertEquals(expectedTags, li.tagCounts().associate { it.tag to it.count }, "tag counts")
    }
}
