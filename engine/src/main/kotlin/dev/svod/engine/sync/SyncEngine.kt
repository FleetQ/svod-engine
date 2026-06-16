package dev.svod.engine.sync

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.security.Secrets
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Multi-machine sync over git: the **same** remote that backs a vault up is its two-way bus. A
 * single user's machines edit one vault and converge without touching git by hand. Topology is a
 * shared remote with one canonical ref `refs/svod/sync/<vaultId>`; every machine is symmetric
 * (no authority/replica) — each fetches the canonical head, reconciles, and pushes it back,
 * retrying on a non-fast-forward (another machine pushed first).
 *
 * **The cycle** (serialized per vault — sync calls under [mutex], and each ref-moving write goes
 * through the engine's single write-actor with an `expectedHead` guard, so a local editor save /
 * agent write / import that lands mid-sync aborts the apply and the cycle re-plans instead of
 * discarding it):
 *  1. commit any pending local changes (the engine already auto-commits writes),
 *  2. fetch `refs/svod/sync/<vaultId>`,
 *  3. up-to-date → done; remote ahead → fast-forward; local ahead → push;
 *     diverged → 3-way merge (clean → merge commit; real conflict → surface, leave local untouched),
 *  4. push `HEAD:refs/svod/sync/<vaultId>` (non-force; reject → re-fetch/merge, bounded retry).
 *
 * **Conflicts are never silently resolved.** A clean merge (incl. structural YAML frontmatter and
 * line-level body merges, via [FrontmatterMerge]) commits automatically. A real overlap (or
 * modify/delete) is recorded in [ConflictStore] with base/ours/theirs and the local tree is left
 * untouched; the user resolves via POST /conflicts/resolve, and the resolving write (an
 * on-change sync trigger) finalizes the merge commit. Nothing is ever lost — full history on
 * every machine, and an incoming file that trips the secret scanner is quarantined, never written.
 */
class SyncEngine(
    private val engine: SvodEngine,
    private val git: SyncGit,
    private val conflicts: ConflictStore,
    private val eventBus: EventBus,
    private val vaultId: String,
    private val hostId: String,
) {
    private val branch = engine.branch()
    private val mutex = Mutex()
    private val author = Author("svod-sync", "sync@svod.localhost")

    /** A merge held open because real conflicts were surfaced; finalized once they all resolve. */
    private data class Pending(val base: String?, val theirs: String, val paths: Set<String>)

    @Volatile
    private var pending: Pending? = null

    /** Sync status surfaced to the API/UI. */
    enum class Status { inSync, syncing, conflicts, offline, error }

    data class Result(val status: Status, val head: String?, val conflicts: Int, val lastSyncedAt: String?)

    @Volatile
    var lastResult: Result? = null
        private set

    private fun record(status: Status, head: String?, syncedAt: String? = lastResult?.lastSyncedAt): Result =
        Result(status, head, conflicts.all().size, syncedAt).also { lastResult = it }

    /** Run one reconcile cycle against [remote] (a URL or a Secrets ref). Never throws. */
    suspend fun sync(remote: String): Result = mutex.withLock { runCycle(remote) }

    private suspend fun runCycle(remote: String): Result {
        val resolved = try { Secrets.resolve(remote) } catch (_: Exception) { remote }
        // 1. Fold any out-of-band working-tree edits into history first (engine writes already commit).
        runCatching { engine.ingestExternalChanges(author) }

        // A merge is being held open for human resolution: don't touch anything until it drains.
        pending?.let { p ->
            if (!conflicts.isEmpty()) return record(Status.conflicts, engine.head())
            // All conflicts resolved → finalize the merge commit, then fall through to push.
            if (!finalize(p)) { pending = null } // local moved under us → re-plan from scratch
        }

        var attempt = 0
        val maxAttempts = 5
        while (true) {
            // 2. Fetch the canonical head (missing ref = nobody has pushed yet, not an error).
            try { git.fetchSync(resolved, vaultId) } catch (_: Exception) { return record(Status.offline, engine.head()) }
            val remoteHead = git.syncRef(vaultId)
            val local = engine.head() ?: return record(Status.error, null)

            val needsPush: Boolean = when {
                remoteHead == null -> true                              // first push: create the canonical ref
                local == remoteHead -> return record(Status.inSync, local, Instant.now().toString())
                git.isAncestor(local, remoteHead) -> {                  // remote ahead → fast-forward
                    if (!engine.fastForwardTo(remoteHead, expectedHead = local)) continue // local moved → re-plan
                    return record(Status.inSync, remoteHead, Instant.now().toString())
                }
                git.isAncestor(remoteHead, local) -> true               // local ahead → push
                else -> when (val m = merge(local, remoteHead)) {       // diverged → 3-way merge
                    MergeStep.Aborted -> continue                       // local moved during apply → re-plan
                    MergeStep.Conflicts -> {
                        eventBus.publish(EventTypes.CONFLICT) { put("source", "sync"); put("vault", vaultId); put("count", conflicts.all().size) }
                        return record(Status.conflicts, engine.head())
                    }
                    MergeStep.Merged -> true                            // clean merge commit created → push it
                }
            }

            if (needsPush) when (git.pushSync(resolved, branch, vaultId)) {
                SyncGit.PushResult.OK -> {
                    val head = engine.head()
                    eventBus.publish(EventTypes.INDEX_UPDATED) { put("vault", vaultId); put("syncHead", head ?: "") }
                    return record(Status.inSync, head, Instant.now().toString())
                }
                SyncGit.PushResult.REJECTED -> {                        // a peer pushed first → re-fetch/merge
                    if (++attempt >= maxAttempts) return record(Status.error, engine.head())
                    delay(backoffMillis(attempt))
                }
                SyncGit.PushResult.ERROR -> return record(Status.offline, engine.head())
            }
        }
    }

    private sealed interface MergeStep { object Merged : MergeStep; object Conflicts : MergeStep; object Aborted : MergeStep }

    /**
     * Diverged 3-way merge of [theirs] into [ours]. Clean → a merge commit (parents ours+theirs);
     * any real conflict → record base/ours/theirs, leave the local tree untouched, and hold the
     * merge [pending] for human resolution.
     */
    private suspend fun merge(ours: String, theirs: String): MergeStep {
        val base = git.mergeBase(ours, theirs)
        val plan = plan(base, ours, theirs, forceOurs = emptySet())
        if (plan.conflicts.isNotEmpty()) {
            plan.conflicts.forEach { conflicts.record(it.path, it.base, it.ours, it.theirs, it.reasons) }
            pending = Pending(base, theirs, plan.conflicts.map { it.path }.toSet())
            return MergeStep.Conflicts
        }
        val msg = "sync: merge ${theirs.take(8)} into ${ours.take(8)} on $hostId"
        val applied = engine.applyMerge(plan.writes, plan.deletes, theirs, msg, author, expectedHead = ours)
        return if (applied != null) MergeStep.Merged else MergeStep.Aborted
    }

    /**
     * Finalize a [pending] merge after its conflicts were resolved: the resolved files now live in
     * the local tree (HEAD advanced), so re-plan against the stored base/theirs but force the
     * resolved paths to keep ours, then create the merge commit linking [Pending.theirs].
     */
    private suspend fun finalize(p: Pending): Boolean {
        val ours = engine.head() ?: return false
        if (git.isAncestor(p.theirs, ours)) { pending = null; return true } // already folded in
        // The previously-conflicted paths now hold the user's resolution → keep ours for those; other
        // paths still merge normally so theirs' clean changes are not dropped.
        val plan = plan(p.base, ours, p.theirs, forceOurs = p.paths)
        // A non-conflicted path might newly collide (a fresh edit raced the resolution) — re-surface it.
        if (plan.conflicts.isNotEmpty()) {
            plan.conflicts.forEach { conflicts.record(it.path, it.base, it.ours, it.theirs, it.reasons) }
            pending = Pending(p.base, p.theirs, plan.conflicts.map { it.path }.toSet())
            return true
        }
        val msg = "sync: finalize merge ${p.theirs.take(8)} into ${ours.take(8)} on $hostId"
        val applied = engine.applyMerge(plan.writes, plan.deletes, p.theirs, msg, author, expectedHead = ours)
        pending = null
        return applied != null
    }

    private data class Conflict(val path: String, val base: String?, val ours: String?, val theirs: String?, val reasons: List<String>)
    private data class Plan(val writes: Map<String, String>, val deletes: List<String>, val conflicts: List<Conflict>)

    /**
     * File-by-file 3-way between [base], [ours], [theirs]. Paths in [forceOurs] keep ours verbatim
     * (used at finalize so a user's resolution wins). Incoming content that trips the secret scanner
     * is quarantined as a conflict, never written.
     */
    private fun plan(base: String?, ours: String, theirs: String, forceOurs: Set<String>): Plan {
        val baseFiles = base?.let { git.filesAt(it) } ?: emptyMap()
        val ourFiles = git.filesAt(ours)
        val theirFiles = git.filesAt(theirs)

        val writes = LinkedHashMap<String, String>()
        val deletes = ArrayList<String>()
        val conflicts = ArrayList<Conflict>()

        for (path in (ourFiles.keys + theirFiles.keys + baseFiles.keys)) {
            val ob = ourFiles[path]; val tb = theirFiles[path]; val bb = baseFiles[path]
            when {
                path in forceOurs && ob != null -> {}                  // resolution / local wins → keep ours
                ob == tb -> {}                                          // identical on both sides
                tb == bb -> {}                                          // theirs unchanged → keep ours
                ob == bb -> {                                           // ours unchanged → take theirs
                    if (tb == null) deletes.add(path)
                    else takeIncoming(path, git.read(theirs, path)!!, bb?.let { git.read(base!!, path) }, conflicts, writes)
                }
                ob == null || tb == null ->                            // modify/delete (or rename/edit) → real conflict
                    conflicts.add(Conflict(path, bb?.let { git.read(base!!, path) }, ob?.let { git.read(ours, path) }, tb?.let { git.read(theirs, path) },
                        listOf("file removed on one machine and modified on another")))
                path.endsWith(".md") -> when (val out = FrontmatterMerge.merge(bb?.let { git.read(base!!, path) }, git.read(ours, path)!!, git.read(theirs, path)!!)) {
                    is FrontmatterMerge.Outcome.Merged -> takeIncoming(path, out.content, bb?.let { git.read(base!!, path) }, conflicts, writes)
                    is FrontmatterMerge.Outcome.Conflict -> conflicts.add(Conflict(path, out.base, out.ours, out.theirs, out.reasons))
                }
                else -> conflicts.add(Conflict(path, bb?.let { git.read(base!!, path) }, git.read(ours, path), git.read(theirs, path),
                    listOf("binary/non-markdown file changed on both machines")))
            }
        }
        return Plan(writes, deletes, conflicts)
    }

    /** Stage [content] for [path] unless it trips the secret scanner — then quarantine it as a conflict. */
    private fun takeIncoming(path: String, content: String, base: String?, conflicts: MutableList<Conflict>, writes: MutableMap<String, String>) {
        val findings = engine.scanSecrets(content)
        if (findings.isNotEmpty()) conflicts.add(Conflict(path, base, null, content, listOf("incoming file quarantined — secret(s) detected: ${findings.joinToString(", ")}")))
        else writes[path] = content
    }

    private fun backoffMillis(attempt: Int): Long = (50L shl (attempt - 1)).coerceAtMost(800L)
}
