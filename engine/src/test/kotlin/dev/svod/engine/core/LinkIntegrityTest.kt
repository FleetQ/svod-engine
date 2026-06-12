package dev.svod.engine.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val MOVER = Author("mover", "mover@svod.test")

class LinkIntegrityTest {

    @Test
    fun `moving a note rewrites all backlinks in one commit`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            e.write("notes/b.md", "# B\nthe target note", expectedRevision = null, author = MOVER)
            e.write(
                "notes/a.md",
                "# A\nsee [[b]] and [[notes/b]] and [[b|alias]] and [[b#section]]",
                expectedRevision = null, author = MOVER,
            )
            e.write("notes/unrelated.md", "# U\nlinks to [[a]] only", expectedRevision = null, author = MOVER)

            val bRev = e.read("notes/b.md")!!.revision
            val moved = e.moveWithLinks("notes/b.md", "notes/c.md", expectedRevision = bRev, author = MOVER)

            assertTrue(moved.outcome is WriteOutcome.Success, "got ${moved.outcome}")
            assertEquals(listOf("notes/a.md"), moved.rewrittenBacklinks, "only the backlinking note is rewritten")

            // every link form now points at c, preserving alias/heading
            val a = e.read("notes/a.md")!!.text
            assertTrue("[[c]]" in a, "basename link rewritten: $a")
            assertTrue("[[notes/c]]" in a, "path link rewritten: $a")
            assertTrue("[[c|alias]]" in a, "alias preserved: $a")
            assertTrue("[[c#section]]" in a, "heading preserved: $a")
            assertTrue("[[b" !in a, "no stale links to b remain: $a")

            // move + rewrite landed in ONE commit (same commit id touches both files)
            val moveCommit = (moved.outcome as WriteOutcome.Success).commit
            assertEquals(moveCommit, e.history("notes/c.md").first().commit)
            assertEquals(moveCommit, e.history("notes/a.md").first().commit)

            // the unrelated note was untouched
            assertTrue("links to [[a]] only" in e.read("notes/unrelated.md")!!.text)

            assertTrue(GitCli.isWorkingTreeClean(fx.root))
            assertTrue(GitCli.fsckClean(fx.root))
        }
    }

    @Test
    fun `ambiguous basename links are left alone`() = runBlocking {
        VaultFixture.create().use { fx ->
            val e = fx.open()
            // two notes share basename "dup" → [[dup]] is ambiguous, must not be rewritten
            e.write("x/dup.md", "# X dup", expectedRevision = null, author = MOVER)
            e.write("y/dup.md", "# Y dup", expectedRevision = null, author = MOVER)
            e.write("ref.md", "# Ref\nsee [[dup]]", expectedRevision = null, author = MOVER)

            val rev = e.read("x/dup.md")!!.revision
            val moved = e.moveWithLinks("x/dup.md", "x/renamed.md", expectedRevision = rev, author = MOVER)
            assertTrue(moved.rewrittenBacklinks.isEmpty(), "ambiguous [[dup]] must not be rewritten")
            assertTrue("[[dup]]" in e.read("ref.md")!!.text)
        }
    }
}
