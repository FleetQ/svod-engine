package dev.svod.engine.sync

import dev.svod.engine.core.Author
import dev.svod.engine.core.GitCli
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.events.SvodEvent
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.watch.FileWatcher
import dev.svod.engine.security.SecretScanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/** A [SyncGit] whose network calls can be held open, to stand in for a slow fetch/push on a large vault. */
private class GatedSyncGit(root: Path) : SyncGit(root) {
    @Volatile var holdFetch: CompletableDeferred<Unit>? = null
    @Volatile var holdPush: CompletableDeferred<Unit>? = null
    val inFetch = CompletableDeferred<Unit>()
    val inPush = CompletableDeferred<Unit>()

    override fun fetchSync(remote: String, vaultId: String) {
        holdFetch?.let { inFetch.complete(Unit); runBlocking { it.await() } }
        super.fetchSync(remote, vaultId)
    }

    override fun pushSync(remote: String, source: String, vaultId: String): PushResult {
        holdPush?.let { inPush.complete(Unit); runBlocking { it.await() } }
        return super.pushSync(remote, source, vaultId)
    }
}

class SyncEngineTest {

    private fun remoteSyncRef(c: Cluster): String? =
        Git.open(c.bare.toFile()).use { it.repository.resolve("refs/svod/sync/$V")?.name }

    /**
     * Lock scope: the network fetch must not hold anything an ordinary write needs. While B's cycle
     * is stuck in a slow fetch, a write on B completes at once; the cycle then picks the write up in
     * its snapshot (it is pinned after the fetch) and merges it with A's incoming change.
     */
    @Test
    fun `a write during a slow fetch completes promptly and is not lost`() = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            val git = GatedSyncGit(c.dirB)
            val syncB = SyncEngine(c.engineB, git, c.conflictsB, EventBus(), V, "machineB")
            c.engineA.write("notes/from-a.md", "# from A", null, T)
            c.syncA.sync(c.remote)                                  // remote is now ahead of B

            val gate = CompletableDeferred<Unit>()
            git.holdFetch = gate
            val cycle = c.scope.async { syncB.sync(c.remote) }
            withTimeout(10_000) { git.inFetch.await() }             // B's cycle is inside the fetch

            val t0 = System.nanoTime()
            withTimeout(5_000) { c.engineB.write("notes/during-fetch.md", "# written mid-fetch", null, T) }
            val writeMs = (System.nanoTime() - t0) / 1_000_000
            assertFalse(cycle.isCompleted, "the sync is still blocked in its fetch")
            assertTrue(writeMs < 2_000, "a write must not wait for the network (took $writeMs ms)")

            gate.complete(Unit)
            val r = withTimeout(20_000) { cycle.await() }
            assertEquals(SyncEngine.Status.inSync, r.status)
            assertNotNull(c.engineB.read("notes/during-fetch.md"), "the mid-fetch write survives")
            assertNotNull(c.engineB.read("notes/from-a.md"), "the incoming change is merged in")
            assertEquals(c.engineB.head(), remoteSyncRef(c), "the merge (incl. the mid-fetch write) was pushed")
        }
    }

    /**
     * Snapshot semantics: a cycle pushes the commit it pinned, not whatever the branch points at by
     * the time the (slow) push runs. A commit that lands during the push stays local, intact, and
     * is carried by the next cycle — nothing is lost and nothing is pushed half-planned.
     */
    @Test
    fun `a commit during a slow push survives and is synced by the next cycle`(): Unit = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            val git = GatedSyncGit(c.dirB)
            val syncB = SyncEngine(c.engineB, git, c.conflictsB, EventBus(), V, "machineB")
            c.engineB.write("notes/b1.md", "# b1", null, T)
            val snapshot = c.engineB.head()

            val gate = CompletableDeferred<Unit>()
            git.holdPush = gate
            val cycle = c.scope.async { syncB.sync(c.remote) }
            withTimeout(10_000) { git.inPush.await() }
            withTimeout(5_000) { c.engineB.write("notes/b2.md", "# b2 — written mid-push", null, T) }
            gate.complete(Unit)

            val r = withTimeout(20_000) { cycle.await() }
            assertEquals(SyncEngine.Status.inSync, r.status)
            assertEquals(snapshot, r.head, "the cycle reports the snapshot it reconciled")
            assertEquals(snapshot, remoteSyncRef(c), "exactly the pinned commit was pushed")
            val local = c.engineB.head()
            assertTrue(local != snapshot && c.engineB.read("notes/b2.md") != null, "the mid-push commit is intact locally")

            git.holdPush = null
            assertEquals(SyncEngine.Status.inSync, syncB.sync(c.remote).status) // the trailing cycle
            assertEquals(local, remoteSyncRef(c), "the next cycle pushes the commit that arrived mid-push")
            c.syncA.sync(c.remote)
            assertNotNull(c.engineA.read("notes/b2.md"), "and it reaches the other machine")
        }
    }

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

    /**
     * Sync writes the working tree (fast-forward = reset --hard, merge = file writes). The live
     * FileWatcher sees those FS events; if its ingest turned them into a COMMIT_CREATED, the
     * SyncScheduler's on-change trigger would re-fire after every sync that pulled anything —
     * a self-sustaining sync loop. The writes run on the write-actor and are committed there, so
     * the watcher's path-scoped ingest must find nothing to commit.
     */
    @Test
    fun `sync writes into the working tree do not come back as external commits`() = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            val bus = EventBus()
            val index = IndexService(c.dirB, c.dirB.resolve(".svod").resolve("index"), NoneEmbedder).start()
            val watcher = FileWatcher(c.dirB, c.engineB, index, bus, vaultId = V).start()
            val commits = CopyOnWriteArrayList<SvodEvent>()
            val collector = c.scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { if (it.type == EventTypes.COMMIT_CREATED) commits.add(it) } }
            try {
                // Fast-forward: A adds notes, B pulls them straight into its working tree.
                for (i in 1..20) c.engineA.write("notes/ff-$i.md", "# FF $i\nfrom A", null, T)
                c.syncA.sync(c.remote)
                assertEquals(SyncEngine.Status.inSync, c.syncB.sync(c.remote).status)
                // Diverged merge: B's working tree gets A's files written by applyMerge.
                for (i in 1..20) c.engineA.write("notes/mg-$i.md", "# MG $i\nfrom A", null, T)
                c.syncA.sync(c.remote)
                c.engineB.write("notes/local.md", "# local on B", null, T)
                assertEquals(SyncEngine.Status.inSync, c.syncB.sync(c.remote).status)
                assertNotNull(c.engineB.read("notes/mg-20.md"))
                val headAfterSync = c.engineB.head()

                delay(2_000) // FS events + the watcher's debounce + ingest have long settled
                assertTrue(commits.isEmpty(), "sync's own working-tree writes must not re-enter as commits: ${commits.map { it.data }}")
                assertEquals(headAfterSync, c.engineB.head(), "no extra external commit on top of the sync result")

                // Positive control: the watcher IS live — a genuine outside edit does produce a commit.
                Files.writeString(c.dirB.resolve("notes/outside.md"), "# edited outside the engine")
                withTimeout(10_000) { while (commits.isEmpty()) delay(25) }
                assertEquals("external", commits.single().data["author"]?.toString()?.trim('"'))
            } finally {
                collector.cancel(); watcher.close(); index.close()
            }
        }
    }
}
