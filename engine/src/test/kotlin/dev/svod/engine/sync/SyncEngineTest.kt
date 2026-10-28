package dev.svod.engine.sync

import dev.svod.engine.core.Author
import dev.svod.engine.core.GitCli
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.events.SvodEvent
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.lifecycle.SvodConfig
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val T = Author("tester", "t@svod.test")
// Assembled at runtime so no key header is committed as a literal.
private val PEM_BLOCK = "-----BEGIN " + "RSA PRIVATE KEY-----\nMIIabc\n-----END " + "RSA PRIVATE KEY-----"
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

/**
 * An HTTP "remote" that accepts every connection and never sends a byte — the shape of the
 * 2026-10 hang (a TLS/HTTP read parked forever in jgit). Connections are held open until [close].
 */
private class SilentRemote : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val held = CopyOnWriteArrayList<Socket>()
    val url = "http://127.0.0.1:${server.localPort}/vault.git"

    init {
        Thread {
            while (!server.isClosed) runCatching { held.add(server.accept()) }
        }.apply { isDaemon = true }.start()
    }

    override fun close() {
        server.close()
        held.forEach { runCatching { it.close() } }
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

    // ---- 08.10.2026: a quarantined add/add conflict that could not be resolved for 4 days ----

    /** The same new note on both machines (no common ancestor) → an add/add conflict on B. */
    private suspend fun addAddConflict(c: Cluster, path: String) {
        c.engineA.write(path, "# s\nfrom A\n", null, T)
        c.syncA.sync(c.remote)
        c.engineB.write(path, "# s\nfrom B\n", null, T)
        assertEquals(SyncEngine.Status.conflicts, c.syncB.sync(c.remote).status)
        assertTrue(c.conflictsB.all().single { it.path == path }.base.isNullOrEmpty(), "add/add: no common ancestor")
    }

    @Test
    fun `a resolved add-add conflict converges even when the sync engine restarts before the next cycle`(): Unit = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            addAddConflict(c, "messy/s.md")
            // Resolve the way POST /conflicts/resolve does: write the merged text, clear the conflict.
            val rev = c.engineB.read("messy/s.md")!!.revision
            c.engineB.write("messy/s.md", "# s\nfrom A and B\n", rev, T)
            c.conflictsB.resolve("messy/s.md")

            // The engine restarts (the held merge lived only in memory); same vault, same conflict store.
            val restarted = SyncEngine(c.engineB, SyncGit(c.dirB), c.conflictsB, EventBus(), V, "machineB")
            val r = restarted.sync(c.remote)
            assertEquals(SyncEngine.Status.inSync, r.status, "conflicts came back: ${c.conflictsB.all().map { it.path to it.reasons }}")
            assertEquals(c.engineB.head(), remoteSyncRef(c), "the resolution was pushed")
            c.syncA.sync(c.remote)
            assertEquals(c.engineA.head(), c.engineB.head())
            assertEquals("# s\nfrom A and B\n", c.engineA.read("messy/s.md")!!.text)
            assertEquals(SyncEngine.Status.inSync, restarted.sync(c.remote).status, "and stays clean")
        }
    }

    @Test
    fun `an open conflict does not stop the cycle from fetching, and a peer that converges clears it`(): Unit = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            addAddConflict(c, "messy/s.md")
            c.engineA.write("notes/later.md", "# later", null, T)
            c.syncA.sync(c.remote)                                   // the remote moves on

            assertEquals(SyncEngine.Status.conflicts, c.syncB.sync(c.remote).status)
            assertEquals(remoteSyncRef(c), SyncGit(c.dirB).use { it.syncRef(V) }, "B fetched the new remote head")

            // The other machine writes the same content B has → nothing left to decide.
            val revA = c.engineA.read("messy/s.md")!!.revision
            c.engineA.write("messy/s.md", c.engineB.read("messy/s.md")!!.text, revA, T)
            c.syncA.sync(c.remote)
            assertEquals(SyncEngine.Status.inSync, c.syncB.sync(c.remote).status)
            assertTrue(c.conflictsB.isEmpty(), "stale conflict cleared: ${c.conflictsB.all().map { it.path }}")
            assertNotNull(c.engineB.read("notes/later.md"))
        }
    }

    /** A peer (scanner off) adds a file holding a key; B (scanner on) quarantines it in a diverged merge. */
    private suspend fun quarantined(c: Cluster): ConflictStore.SyncConflict {
        c.engineA.write("leak.md", "---\ntitle: leak\n---\n" + PEM_BLOCK + "\n", null, T)
        c.syncA.sync(c.remote)
        c.engineB.write("notes/ok.md", "# ok\n", null, T)
        assertEquals(SyncEngine.Status.conflicts, c.syncB.sync(c.remote).status)
        return c.conflictsB.all().single { it.path == "leak.md" }.also { assertTrue(it.quarantined) }
    }

    @Test
    fun `a quarantined incoming file can be accepted on purpose`(): Unit = runBlocking {
        Cluster(scanA = SecretScanner.OFF, scanB = SecretScanner(enabled = true)).use { c ->
            c.bootstrap()
            val q = quarantined(c)
            // A normal write of that text is still refused — the override is a separate, explicit choice.
            assertTrue(c.engineB.write("leak.md", q.theirs!!, null, T) is dev.svod.engine.core.WriteOutcome.Blocked)

            c.conflictsB.resolve("leak.md", ConflictStore.Choice.incoming)
            assertEquals(SyncEngine.Status.inSync, c.syncB.sync(c.remote).status)
            assertEquals(q.theirs, c.engineB.read("leak.md")!!.text, "the incoming version landed as is")
            val msg = Git.open(c.dirB.toFile()).use { it.log().setMaxCount(1).call().first().fullMessage }
            assertTrue("secret scan overridden by the user for leak.md" in msg, "override recorded in history: $msg")
            assertEquals(c.engineB.head(), remoteSyncRef(c))
            c.syncA.sync(c.remote)
            assertEquals(c.engineA.head(), c.engineB.head())
            assertEquals(SyncEngine.Status.inSync, c.syncB.sync(c.remote).status, "nothing comes back")
        }
    }

    @Test
    fun `a quarantined incoming file can be rejected, keeping the local side`(): Unit = runBlocking {
        Cluster(scanA = SecretScanner.OFF, scanB = SecretScanner(enabled = true)).use { c ->
            c.bootstrap()
            quarantined(c)
            c.conflictsB.resolve("leak.md", ConflictStore.Choice.ours)
            assertEquals(SyncEngine.Status.inSync, c.syncB.sync(c.remote).status)
            assertNull(c.engineB.read("leak.md"), "never written on B")
            assertNotNull(c.engineB.read("notes/ok.md"))
            // The merge B pushed absorbs the incoming version: the converged vault is B's side.
            c.syncA.sync(c.remote)
            assertEquals(c.engineA.head(), c.engineB.head())
            assertNull(c.engineA.read("leak.md"), "the peer converges to the kept side (the file stays in history)")
            assertEquals(SyncEngine.Status.inSync, c.syncB.sync(c.remote).status)
        }
    }

    @Test
    fun `a resolution is durable across a restart of the whole engine`(): Unit = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            val file = c.dirB.resolve(".svod").resolve("sync-resolutions.json")
            val store = ConflictStore(file)
            val syncB = SyncEngine(c.engineB, SyncGit(c.dirB), store, EventBus(), V, "machineB")
            c.engineA.write("messy/s.md", "# s\nfrom A\n", null, T)
            c.syncA.sync(c.remote)
            c.engineB.write("messy/s.md", "# s\nfrom B\n", null, T)
            assertEquals(SyncEngine.Status.conflicts, syncB.sync(c.remote).status)
            store.resolve("messy/s.md")                               // keep B's text

            val reopened = ConflictStore(file)                         // new process: open conflicts are gone, the answer is not
            val restarted = SyncEngine(c.engineB, SyncGit(c.dirB), reopened, EventBus(), V, "machineB")
            assertEquals(SyncEngine.Status.inSync, restarted.sync(c.remote).status)
            assertEquals("# s\nfrom B\n", c.engineB.read("messy/s.md")!!.text)
            assertFalse("messy/s.md" in Files.readString(file), "consumed by the merge commit")
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

    /**
     * Regression (engine 1.27.0, 2026-10-08): no jgit transport call had a timeout (jgit's default is
     * wait forever), so a remote that accepted the connection and never answered held a poll cycle
     * in `fetch` for ~19 hours, and every later sync — the manual one too — queued behind it. With a
     * timeout the fetch fails, the cycle ends `offline`, and the next cycle runs normally.
     */
    @Test
    fun `a remote that never answers times out the fetch and frees the cycle`(): Unit = runBlocking {
        Cluster().use { c ->
            SilentRemote().use { silent ->
                val syncA = SyncEngine(c.engineA, SyncGit(c.dirA, timeoutSeconds = 1), c.conflictsA, EventBus(), V, "machineA")

                val r = withTimeout(30_000) { syncA.sync(silent.url) }
                assertEquals(SyncEngine.Status.offline, r.status)
                assertNull(syncA.phase, "the cycle is over, not parked in fetch")

                // The mutex is free: the next cycle (a reachable remote) runs and pushes.
                assertEquals(SyncEngine.Status.inSync, withTimeout(30_000) { syncA.sync(c.remote) }.status)
                assertEquals(c.engineA.head(), remoteSyncRef(c))

                // Through the scheduler: the timed-out cycle finishes, nothing stays running.
                val backup = BackupService(listOf(BackupService.Binding(
                    V, c.dirA, SvodConfig.BackupSettings("unused-remote", enabled = true, syncEnabled = true), store = null,
                )))
                val scheduler = SyncScheduler(c.scope, backup, { syncA.sync(silent.url) })
                assertEquals(SyncEngine.Status.offline, withTimeout(30_000) { scheduler.syncNow(V).await() }?.status)
                assertFalse(scheduler.isRunning(V), "no cycle is left running after the timeout")
                assertNull(scheduler.running(V))
                assertEquals(SyncEngine.Status.offline, withTimeout(30_000) { scheduler.syncNow(V).await() }?.status, "a second sync starts and ends too")
                scheduler.stop()
            }
        }
    }

    @Test
    fun `pushSync to a remote that never answers times out as ERROR`(): Unit = runBlocking {
        Cluster().use { c ->
            SilentRemote().use { silent ->
                val t0 = System.nanoTime()
                val result = SyncGit(c.dirA, timeoutSeconds = 1).use { it.pushSync(silent.url, "master", V) }
                assertEquals(SyncGit.PushResult.ERROR, result)
                assertTrue((System.nanoTime() - t0) / 1_000_000 < 20_000, "push gave up on the timeout")
            }
        }
    }

    @Test
    fun `clone from a remote that never answers fails instead of hanging`() {
        SilentRemote().use { silent ->
            val dest = Files.createTempDirectory("svod-clone-").also { Files.delete(it) }
            val t0 = System.nanoTime()
            assertFailsWith<Exception> { SyncBootstrap.clone(silent.url, dest, V, timeoutSeconds = 1) }
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 20_000, "clone gave up on the timeout")
        }
    }

    // ---- #39: a merge that carries a soft delete threw on `.trash/` and left a half-applied tree ----

    private fun log(dir: Path) = Git.open(dir.toFile()).use { g -> g.log().call().toList() }

    @Test
    fun `a merge carrying a soft delete is a real merge commit with the trash entry and no external commit`(): Unit = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            c.seedShared("notes/gone.md", "# gone\n")
            val rev = c.engineA.read("notes/gone.md")!!.revision
            assertTrue(c.engineA.delete("notes/gone.md", rev, T) is dev.svod.engine.core.WriteOutcome.Success)
            c.syncA.sync(c.remote)                                       // canonical carries the soft delete
            c.engineB.write("notes/other.md", "# other\n", null, T)     // B diverges on another file

            assertEquals(SyncEngine.Status.inSync, c.syncB.sync(c.remote).status)
            val head = log(c.dirB).first()
            assertEquals(2, head.parentCount, "a merge commit with both parents: ${head.fullMessage}")
            assertTrue(head.fullMessage.startsWith("sync: merge"), head.fullMessage)
            assertTrue(Files.isRegularFile(c.dirB.resolve(".trash/notes/gone.md")), "the .trash/ entry came over")
            assertNull(c.engineB.read("notes/gone.md"))
            assertNotNull(c.engineB.read("notes/other.md"))

            assertEquals(SyncEngine.Status.inSync, c.syncB.sync(c.remote).status)
            c.syncA.sync(c.remote)
            assertEquals(c.engineA.head(), c.engineB.head())
            assertTrue(Files.isRegularFile(c.dirA.resolve(".trash/notes/gone.md")))
            for (d in listOf(c.dirA, c.dirB)) {
                assertTrue(log(d).none { it.fullMessage.startsWith("external:") }, "no half-applied merge was ingested as an external commit")
            }
        }
    }

    /**
     * The state the stuck 1.28.0 vault is in: an earlier failed merge wrote part of the incoming side
     * (one file exactly as theirs, one note already merged with both sides' edits) and the next cycle
     * committed it as `external: ingest working-tree changes`. The next merge must still be clean:
     * hunks that are identical on both sides are not a conflict.
     */
    @Test
    fun `a vault with a partially ingested earlier merge still merges cleanly`(): Unit = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            c.seedShared("ledger.md", "# Ledger\n\n## A\n- a1\n\n## B\n- b1\n")
            c.seedShared("notes/gone.md", "# gone\n")
            c.seedShared("plain.txt", "v1\n")

            // A: appends to its own ledger section, edits plain.txt, soft-deletes a note.
            c.engineA.write("ledger.md", "# Ledger\n\n## A\n- a1\n- a2\n\n## B\n- b1\n", c.engineA.read("ledger.md")!!.revision, T)
            c.engineA.write("plain.txt", "v2 from A\n", c.engineA.read("plain.txt")!!.revision, T)
            c.engineA.delete("notes/gone.md", c.engineA.read("notes/gone.md")!!.revision, T)
            c.syncA.sync(c.remote)

            // B: appends to its own section → diverged.
            c.engineB.write("ledger.md", "# Ledger\n\n## A\n- a1\n\n## B\n- b1\n- b2\n", c.engineB.read("ledger.md")!!.revision, T)

            // What the failed 1.28.0 apply left behind, committed by the next ingest as "external".
            Files.writeString(c.dirB.resolve("plain.txt"), "v2 from A\n")
            Files.writeString(c.dirB.resolve("ledger.md"), "# Ledger\n\n## A\n- a1\n- a2\n\n## B\n- b1\n- b2\n")
            assertNotNull(c.engineB.ingestExternalChanges())

            assertEquals(SyncEngine.Status.inSync, c.syncB.sync(c.remote).status, "conflicts: ${c.conflictsB.all().map { it.path to it.reasons }}")
            assertEquals(2, log(c.dirB).first().parentCount, "the merge is a real merge commit")
            assertEquals("# Ledger\n\n## A\n- a1\n- a2\n\n## B\n- b1\n- b2\n", c.engineB.read("ledger.md")!!.text)
            assertTrue(Files.isRegularFile(c.dirB.resolve(".trash/notes/gone.md")))
            assertNull(c.engineB.read("notes/gone.md"))
            c.syncA.sync(c.remote)
            assertEquals(c.engineA.head(), c.engineB.head())
            assertEquals(SyncEngine.Status.inSync, c.syncB.sync(c.remote).status)
        }
    }

    @Test
    fun `an incoming binary file arrives byte for byte, added and then changed`(): Unit = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            // A PNG header plus bytes that are not valid UTF-8; a text round-trip turns them into U+FFFD.
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0xFF.toByte(), 0xC3.toByte(), 0x28)
            // B always has a local edit of its own, so it takes A's file through a 3-way merge, not a fast-forward.
            c.engineA.writeBytes("img/shot.png", png, null, T)
            c.engineB.write("notes/b1.md", "# b1\n", null, T)
            c.syncA.sync(c.remote); c.syncB.sync(c.remote)
            assertTrue(png.contentEquals(c.engineB.readBytes("img/shot.png")), "added on A, taken by B unchanged")

            c.syncA.sync(c.remote)
            val png2 = png + byteArrayOf(0x80.toByte(), 0x00, 0xFE.toByte())
            c.engineA.writeBytes("img/shot.png", png2, c.engineA.blobId(png), T)
            c.engineB.write("notes/b2.md", "# b2\n", null, T)
            c.syncA.sync(c.remote); c.syncB.sync(c.remote)
            assertTrue(png2.contentEquals(c.engineB.readBytes("img/shot.png")), "changed on A, taken by B unchanged")
            c.syncA.sync(c.remote)
            assertEquals(c.engineA.head(), c.engineB.head())
        }
    }

    @Test
    fun `applyMerge validates every path before touching the tree`(): Unit = runBlocking {
        Cluster().use { c ->
            c.engineA.write("notes/x.md", "# old\n", null, T)
            val head = c.engineA.head()!!
            assertFailsWith<IllegalArgumentException> {
                c.engineA.applyMerge(
                    mapOf("notes/x.md" to "# new\n", "notes/y.md" to "# y\n", ".git/config" to "nope").mapValues { it.value.toByteArray() },
                    emptyList(), head, "sync: merge", T, expectedHead = head,
                )
            }
            assertEquals("# old\n", c.engineA.read("notes/x.md")!!.text, "nothing was written")
            assertNull(c.engineA.read("notes/y.md"))
            assertEquals(head, c.engineA.head())
            assertNull(c.engineA.ingestExternalChanges(), "nothing left in the tree for an external commit")
        }
    }

    @Test
    fun `a merge that fails part way puts back the files it already wrote`(): Unit = runBlocking {
        Cluster().use { c ->
            c.engineA.write("notes/x.md", "# old\n", null, T)
            c.engineA.write("blocker.md", "# a file, not a directory\n", null, T)
            val head = c.engineA.head()!!
            assertFailsWith<java.io.IOException> {
                c.engineA.applyMerge(
                    mapOf("notes/x.md" to "# new\n", "notes/new.md" to "# n\n", "blocker.md/child.md" to "# cannot be written\n").mapValues { it.value.toByteArray() },
                    emptyList(), head, "sync: merge", T, expectedHead = head,
                )
            }
            assertEquals("# old\n", c.engineA.read("notes/x.md")!!.text, "the overwritten file is restored")
            assertNull(c.engineA.read("notes/new.md"), "the added file is removed again")
            assertEquals(head, c.engineA.head())
            assertNull(c.engineA.ingestExternalChanges(), "nothing left in the tree for an external commit")
        }
    }

    // ---- #38: a sync held on conflicts, or failing to reach its remote, said nothing for days ----

    private fun captureSyncLog(): Pair<ch.qos.logback.classic.Logger, ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>> {
        val logger = org.slf4j.LoggerFactory.getLogger(SyncEngine::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return logger to appender
    }

    private fun ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>.warns(text: String) =
        list.filter { it.level == ch.qos.logback.classic.Level.WARN && text in it.formattedMessage }

    @Test
    fun `a held sync warns once an hour with count and age and reports its age for metrics`(): Unit = runBlocking {
        Cluster().use { c ->
            c.bootstrap()
            var now = java.time.Instant.parse("2026-10-16T10:00:00Z")
            val syncB = SyncEngine(c.engineB, SyncGit(c.dirB), c.conflictsB, EventBus(), V, "machineB", clock = { now })
            c.engineA.write("messy/s.md", "# s\nfrom A\n", null, T)
            c.syncA.sync(c.remote)
            c.engineB.write("messy/s.md", "# s\nfrom B\n", null, T)

            val (logger, appender) = captureSyncLog()
            try {
                assertEquals(SyncEngine.Status.conflicts, syncB.sync(c.remote).status)
                now = now.plusSeconds(10 * 60)
                assertEquals(SyncEngine.Status.conflicts, syncB.sync(c.remote).status)
                assertEquals(1, appender.warns("held on 1 unresolved conflict").size, "rate-limited: ${appender.list.map { it.formattedMessage }}")
                assertEquals(600, syncB.heldSeconds())
                assertEquals(600, syncB.outOfSyncSeconds())

                now = now.plusSeconds(51 * 60)
                assertEquals(SyncEngine.Status.conflicts, syncB.sync(c.remote).status)
                val warns = appender.warns("held on 1 unresolved conflict")
                assertEquals(2, warns.size, "repeated after an hour")
                assertTrue("'v'" in warns.last().formattedMessage && "1h 1m" in warns.last().formattedMessage && "messy/s.md" in warns.last().formattedMessage, warns.last().formattedMessage)
                assertEquals(61 * 60, syncB.heldSeconds())

                c.conflictsB.resolve("messy/s.md")
                assertEquals(SyncEngine.Status.inSync, syncB.sync(c.remote).status)
                assertEquals(0, syncB.heldSeconds())
                assertEquals(0, syncB.outOfSyncSeconds())
            } finally { logger.detachAppender(appender) }
        }
    }

    @Test
    fun `a fetch failure logs its cause, rate-limited, and a push error logs its cause too`(): Unit = runBlocking {
        Cluster().use { c ->
            var now = java.time.Instant.parse("2026-10-16T10:00:00Z")
            val missing = Files.createTempDirectory("svod-missing-").resolve("nope.git").toString()
            val syncA = SyncEngine(c.engineA, SyncGit(c.dirA), c.conflictsA, EventBus(), V, "machineA", clock = { now })
            val (logger, appender) = captureSyncLog()
            try {
                repeat(3) {
                    assertEquals(SyncEngine.Status.offline, syncA.sync(missing).status)
                    now = now.plusSeconds(10 * 60)
                }
                assertEquals(1, appender.warns("fetch failed").size, "once, not every cycle: ${appender.list.map { it.formattedMessage }}")
                assertTrue("Exception" in appender.warns("fetch failed").single().formattedMessage, "the cause is in the line")
                assertEquals(30 * 60, syncA.outOfSyncSeconds())

                now = now.plusSeconds(60 * 60)
                assertEquals(SyncEngine.Status.offline, syncA.sync(missing).status)
                assertEquals(2, appender.warns("fetch failed").size, "repeated after an hour")
                assertTrue("4 failed cycle(s)" in appender.warns("fetch failed").last().formattedMessage, appender.warns("fetch failed").last().formattedMessage)

                assertEquals(SyncEngine.Status.inSync, syncA.sync(c.remote).status)
                assertEquals(0, syncA.outOfSyncSeconds())
                assertTrue(appender.list.any { "back in sync" in it.formattedMessage })

                // Push: a fetch that succeeds and a push that errors names the push's cause.
                class FailingPush(root: Path) : SyncGit(root) {
                    override fun pushSync(remote: String, source: String, vaultId: String): PushResult {
                        lastPushFailure = "TransportException: remote hung up"
                        return PushResult.ERROR
                    }
                }
                val pushing = SyncEngine(c.engineA, FailingPush(c.dirA), c.conflictsA, EventBus(), V, "machineA", clock = { now })
                c.engineA.write("notes/new.md", "# new\n", null, T)
                assertEquals(SyncEngine.Status.offline, pushing.sync(c.remote).status)
                assertTrue(appender.warns("push failed").single().formattedMessage.endsWith("TransportException: remote hung up"))
            } finally { logger.detachAppender(appender) }
        }
    }

    @Test
    fun `a transport failure is logged by its root cause without credentials from the URL`() {
        val e = org.eclipse.jgit.api.errors.TransportException(
            "https://x-access-token:ghp_secret@github.com/o/v.git: not authorized",
            org.eclipse.jgit.errors.TransportException("https://ghp_secret@github.com/o/v.git: not authorized"),
        )
        val line = SyncGit.describeFailure(e)
        assertEquals("TransportException: https://github.com/o/v.git: not authorized", line)
        assertEquals("SocketTimeoutException: Read timed out", SyncGit.describeFailure(RuntimeException(java.net.SocketTimeoutException("Read timed out"))))
    }
}
