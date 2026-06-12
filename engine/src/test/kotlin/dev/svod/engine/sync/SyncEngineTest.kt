package dev.svod.engine.sync

import dev.svod.engine.core.Author
import dev.svod.engine.core.GitCli
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val T = Author("tester", "t@svod.test")

/** Two replicated engines (authority + replica) sharing a bare remote. */
private class Cluster : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bare: Path = Files.createTempDirectory("svod-bare-").also {
        Git.init().setBare(true).setDirectory(it.toFile()).call().close()
    }

    val dirA: Path = Files.createTempDirectory("svod-A-")
    val engineA = SvodEngine.open(dirA, scope)
    val conflictsA = ConflictStore()
    val syncA = SyncEngine(engineA, SyncGit(dirA), conflictsA, EventBus(), "A", isMergeAuthority = true, remote = bare.toString())

    lateinit var dirB: Path
    lateinit var engineB: SvodEngine
    lateinit var conflictsB: ConflictStore
    lateinit var syncB: SyncEngine

    suspend fun bootstrap() {
        syncA.sync() // push A's scaffold to the bare remote
        dirB = Files.createTempDirectory("svod-B-")
        Git.cloneRepository().setURI(bare.toString()).setDirectory(dirB.toFile()).call().close()
        engineB = SvodEngine.open(dirB, scope)
        conflictsB = ConflictStore()
        syncB = SyncEngine(engineB, SyncGit(dirB), conflictsB, EventBus(), "B", isMergeAuthority = false, remote = bare.toString())
    }

    override fun close() {
        engineA.close()
        if (::engineB.isInitialized) engineB.close()
    }
}

class SyncEngineTest {

    @Test
    fun `replica proposes, authority merges non-overlapping edits, both converge`() = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            // concurrent edits to different files from the same base
            c.engineA.write("notes/a.md", "# A\nalpha", expectedRevision = null, author = T)
            c.syncA.sync() // bare advances with a.md
            c.engineB.write("notes/b.md", "# B\nbeta", expectedRevision = null, author = T) // B still on the old base

            c.syncB.sync() // B diverged → proposes svod/B (no merge on a replica)
            c.syncA.sync() // authority folds the proposal into the canonical branch
            c.syncB.sync() // B fast-forwards to the merged result

            assertEquals(c.engineA.head(), c.engineB.head(), "both hosts converge to the same HEAD")
            for (e in listOf(c.engineA, c.engineB)) {
                assertNotNull(e.read("notes/a.md"))
                assertNotNull(e.read("notes/b.md"))
            }
            assertTrue(c.conflictsA.isEmpty(), "non-overlapping edits produce no conflicts")
            assertTrue(GitCli.fsckClean(c.dirA) && GitCli.fsckClean(c.dirB))
        }
    }

    private suspend fun Cluster.seedShared(path: String, content: String) {
        engineA.write(path, content, expectedRevision = null, author = T)
        syncA.sync(); syncB.sync() // both hosts now hold the file
    }

    @Test
    fun `concurrent frontmatter edits to the same note merge structurally`() = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            c.seedShared("note.md", "---\ntags: [base]\n---\n# Note\nbody\n")

            val revA = c.engineA.read("note.md")!!.revision
            c.engineA.write("note.md", "---\ntags: [base, fromA]\n---\n# Note\nbody\n", revA, T)
            val revB = c.engineB.read("note.md")!!.revision
            c.engineB.write("note.md", "---\ntags: [base, fromB]\n---\n# Note\nbody\n", revB, T)

            c.syncB.sync(); c.syncA.sync(); c.syncB.sync()

            val merged = c.engineA.read("note.md")!!.text
            assertTrue("fromA" in merged && "fromB" in merged, "tag union: $merged")
            assertEquals(merged, c.engineB.read("note.md")!!.text, "both hosts hold the merged note")
            assertTrue(c.conflictsA.isEmpty())
        }
    }

    @Test
    fun `truly conflicting edits are surfaced, not auto-resolved, and nothing is lost`() = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            c.seedShared("note.md", "---\ntitle: Base\n---\nbody\n")

            val revA = c.engineA.read("note.md")!!.revision
            c.engineA.write("note.md", "---\ntitle: FromA\n---\nbody\n", revA, T)
            val revB = c.engineB.read("note.md")!!.revision
            c.engineB.write("note.md", "---\ntitle: FromB\n---\nbody\n", revB, T)

            c.syncB.sync() // B proposes
            c.syncA.sync() // authority merges → conflict on title

            assertTrue(!c.conflictsA.isEmpty(), "conflict must be surfaced")
            val conflict = c.conflictsA.all().first { it.path == "note.md" }
            // both versions preserved for a 3-way merge (never lost, never silently overwritten)
            assertTrue("FromA" in conflict.ours!!, "ours preserved")
            assertTrue("FromB" in conflict.theirs!!, "theirs preserved")
            // the authority keeps ours in the tree (no silent overwrite by theirs)
            assertTrue("FromA" in c.engineA.read("note.md")!!.text)
            assertTrue(GitCli.fsckClean(c.dirA))
        }
    }
}
