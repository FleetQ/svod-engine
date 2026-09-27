package dev.svod.engine.core

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync's two ref moves (fast-forward, merge commit) are compare-and-swaps against the head the
 * cycle planned from. If the branch moved meanwhile — here a commit made behind the engine's back —
 * the move is refused and the newer commit is left exactly where it was.
 */
class GitRepoCasTest {

    private val a = Author("sync", "sync@svod.test")

    @Test
    fun `fast-forward and merge commit refuse to move a branch that moved since planning`() {
        val dir = Files.createTempDirectory("svod-cas-")
        val repo = GitRepo.openOrInit(dir)
        Files.writeString(dir.resolve("a.md"), "a")
        val c1 = repo.commitAll("c1", a)!!
        Files.writeString(dir.resolve("b.md"), "b")
        val c2 = repo.commitAll("c2", a)!!                        // "theirs": a descendant of c1
        Git.open(dir.toFile()).use { it.reset().setMode(ResetCommand.ResetType.HARD).setRef(c1).call() }

        // Planned from c1, but a commit lands first (made by hand, outside the write-actor).
        Files.writeString(dir.resolve("x.md"), "by hand")
        val moved = repo.commitAll("by hand", a)!!

        assertFalse(repo.fastForwardTo(c2, expectedOld = c1), "fast-forward planned from c1 must not apply")
        assertEquals(moved, repo.headId(), "the hand-made commit is still the head")
        assertTrue(Files.exists(dir.resolve("x.md")))

        Files.writeString(dir.resolve("b.md"), "b")               // the merge's writes into the tree
        assertNull(repo.commitMerge("merge", a, c2, expectedHead = c1), "merge planned from c1 must not apply")
        assertEquals(moved, repo.headId())

        // Planned from the real head, both apply.
        val merge = repo.commitMerge("merge", a, c2, expectedHead = moved)
        assertNotNull(merge)
        assertEquals(merge, repo.headId())
        val parents = Git.open(dir.toFile()).use { g -> g.log().setMaxCount(1).call().first().parents.map { it.name } }
        assertEquals(listOf(moved, c2), parents)

        Git.open(dir.toFile()).use { it.reset().setMode(ResetCommand.ResetType.HARD).setRef(c1).call() }
        assertTrue(repo.fastForwardTo(c2, expectedOld = c1))
        assertEquals(c2, repo.headId())
        assertTrue(Files.exists(dir.resolve("b.md")) && !Files.exists(dir.resolve("x.md")), "working tree follows the ref")
    }
}
