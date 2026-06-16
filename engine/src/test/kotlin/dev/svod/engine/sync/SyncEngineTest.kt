package dev.svod.engine.sync

import dev.svod.engine.core.Author
import dev.svod.engine.core.GitCli
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.security.SecretScanner
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val T = Author("tester", "t@svod.test")
private const val V = "v"

/**
 * Two machines of one user sharing a bare remote, syncing one vault via the canonical
 * `refs/svod/sync/v` ref (the new symmetric topology — no authority/replica).
 */
private class Cluster(
    private val scanA: SecretScanner = SecretScanner.OFF,
    private val scanB: SecretScanner = SecretScanner.OFF,
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bare: Path = Files.createTempDirectory("svod-bare-").also {
        Git.init().setBare(true).setDirectory(it.toFile()).call().close()
    }
    val remote = bare.toString()

    val dirA: Path = Files.createTempDirectory("svod-A-")
    val engineA = SvodEngine.open(dirA, scope, scanA, Author("machineA", "a@svod.local"))
    val conflictsA = ConflictStore()
    val syncA = SyncEngine(engineA, SyncGit(dirA), conflictsA, EventBus(), V, "machineA")

    lateinit var dirB: Path
    lateinit var engineB: SvodEngine
    lateinit var conflictsB: ConflictStore
    lateinit var syncB: SyncEngine

    suspend fun bootstrap() {
        syncA.sync(remote) // push A's scaffold to the canonical sync ref
        dirB = Files.createTempDirectory("svod-B-").also { Files.delete(it) } // clone wants an empty/absent dir
        SyncBootstrap.clone(remote, dirB, V)
        engineB = SvodEngine.open(dirB, scope, scanB, Author("machineB", "b@svod.local"))
        conflictsB = ConflictStore()
        syncB = SyncEngine(engineB, SyncGit(dirB), conflictsB, EventBus(), V, "machineB")
    }

    suspend fun seedShared(path: String, content: String) {
        engineA.write(path, content, expectedRevision = null, author = T)
        syncA.sync(remote); syncB.sync(remote) // both machines now hold the file
    }

    override fun close() {
        engineA.close()
        if (::engineB.isInitialized) engineB.close()
    }
}

class SyncEngineTest {

    @Test
    fun `non-overlapping edits on two machines converge with no conflict`() = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            c.engineA.write("notes/a.md", "# A\nalpha", null, T)
            c.syncA.sync(c.remote)                       // canonical advances with a.md
            c.engineB.write("notes/b.md", "# B\nbeta", null, T) // B still on the old base → diverges

            c.syncB.sync(c.remote)                       // B fetches a.md, merges cleanly, pushes the merge
            c.syncA.sync(c.remote)                       // A fast-forwards to the merged result

            assertEquals(c.engineA.head(), c.engineB.head(), "both machines converge to one HEAD")
            for (e in listOf(c.engineA, c.engineB)) {
                assertNotNull(e.read("notes/a.md")); assertNotNull(e.read("notes/b.md"))
            }
            assertTrue(c.conflictsA.isEmpty() && c.conflictsB.isEmpty(), "non-overlapping edits → no conflicts")
            assertTrue(GitCli.fsckClean(c.dirA) && GitCli.fsckClean(c.dirB))
        }
    }

    @Test
    fun `concurrent frontmatter edits to the same note merge structurally`() = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            c.seedShared("note.md", "---\ntags: [base]\n---\n# Note\nbody\n")

            val revA = c.engineA.read("note.md")!!.revision
            c.engineA.write("note.md", "---\ntags: [base, fromA]\n---\n# Note\nbody\n", revA, T)
            c.syncA.sync(c.remote)
            val revB = c.engineB.read("note.md")!!.revision
            c.engineB.write("note.md", "---\ntags: [base, fromB]\n---\n# Note\nbody\n", revB, T)

            c.syncB.sync(c.remote); c.syncA.sync(c.remote)

            val merged = c.engineA.read("note.md")!!.text
            assertTrue("fromA" in merged && "fromB" in merged, "tag union: $merged")
            assertEquals(merged, c.engineB.read("note.md")!!.text, "both machines hold the merged note")
            assertTrue(c.conflictsA.isEmpty() && c.conflictsB.isEmpty())
        }
    }

    @Test
    fun `truly conflicting edits are surfaced, local left untouched, then resolved and converge`() = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            c.seedShared("note.md", "---\ntitle: Base\n---\nbody\n")

            val revA = c.engineA.read("note.md")!!.revision
            c.engineA.write("note.md", "---\ntitle: FromA\n---\nbody\n", revA, T)
            c.syncA.sync(c.remote)                       // canonical = A's title
            val revB = c.engineB.read("note.md")!!.revision
            c.engineB.write("note.md", "---\ntitle: FromB\n---\nbody\n", revB, T)

            val bHeadBeforeSync = c.engineB.head()
            val r = c.syncB.sync(c.remote)               // diverged + overlapping → conflict surfaced

            assertEquals(SyncEngine.Status.conflicts, r.status)
            assertTrue(!c.conflictsB.isEmpty(), "conflict must be surfaced")
            val conflict = c.conflictsB.all().first { it.path == "note.md" }
            assertTrue("FromB" in conflict.ours!! && "FromA" in conflict.theirs!!, "both versions preserved")
            // The local tree is LEFT UNTOUCHED — no merge committed until the human resolves.
            assertEquals(bHeadBeforeSync, c.engineB.head(), "no merge commit before resolution")
            assertTrue("FromB" in c.engineB.read("note.md")!!.text, "ours unchanged on disk")

            // Resolve: write the merged content + clear the conflict (what POST /conflicts/resolve does).
            val rev = c.engineB.read("note.md")!!.revision
            c.engineB.write("note.md", "---\ntitle: FromA+FromB\n---\nbody\n", rev, T)
            c.conflictsB.resolve("note.md")

            c.syncB.sync(c.remote)                       // finalizes the merge + pushes
            c.syncA.sync(c.remote)                       // A fast-forwards to it

            assertTrue(c.conflictsB.isEmpty(), "conflict cleared after resolution")
            assertEquals(c.engineA.head(), c.engineB.head(), "both machines converge after resolve")
            assertTrue("FromA+FromB" in c.engineA.read("note.md")!!.text)
            assertTrue(GitCli.fsckClean(c.dirA) && GitCli.fsckClean(c.dirB))
        }
    }

    @Test
    fun `an incoming file that trips the secret scanner is quarantined, never written`() = runBlocking {
        // A writes with scanning OFF (so the secret enters its history); B scans incoming on merge.
        Cluster(scanA = SecretScanner.OFF, scanB = SecretScanner(enabled = true)).use { c ->
            c.bootstrap()
            val secret = "---\ntitle: leak\n---\n-----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY-----\n"
            c.engineA.write("leak.md", secret, null, T)
            c.syncA.sync(c.remote)                       // canonical now carries the secret file
            c.engineB.write("notes/ok.md", "# ok\n", null, T) // B diverges so a merge (not a ff) happens

            val r = c.syncB.sync(c.remote)

            assertEquals(SyncEngine.Status.conflicts, r.status)
            assertNull(c.engineB.read("leak.md"), "a leaked secret must never be written into the vault")
            val q = c.conflictsB.all().first { it.path == "leak.md" }
            assertTrue(q.reasons.any { "secret" in it.lowercase() }, "quarantine reason: ${q.reasons}")
        }
    }

    @Test
    fun `pushSync rejects a non-fast-forward and accepts a fast-forward`() = runBlocking {
        Cluster().use { c ->
            c.bootstrap()                                // canonical = H0 on both
            c.engineA.write("a.md", "A", null, T)
            assertEquals(SyncGit.PushResult.OK, SyncGit(c.dirA).use { it.pushSync(c.remote, "master", V) })

            // B is still at H0; a commit on B is NOT a descendant of canonical (now A's H1) → non-ff.
            c.engineB.write("b.md", "B", null, T)
            assertEquals(SyncGit.PushResult.REJECTED, SyncGit(c.dirB).use { it.pushSync(c.remote, "master", V) })
        }
    }
}
