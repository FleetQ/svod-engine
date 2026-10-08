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
 * **The cycle** reconciles a SNAPSHOT: the local HEAD as it stands when a round starts planning. Network
 * fetch/push run outside the write-actor, so ordinary writes never wait on the network; only the
 * short ref-moving step is serialized. That step goes through the engine's single write-actor as a
 * compare-and-swap against the pinned head (`expectedHead`, a JGit `RefUpdate` with the expected
 * old id), so a local editor save / agent write / import that lands mid-sync aborts the apply and
 * the round re-plans instead of discarding it. A push sends the pinned commit, not whatever the
 * branch points at by then; commits that land after the snapshot are left for the next cycle
 * (the scheduler's trailing cycle). Cycles are serialized per vault by [mutex]:
 *  1. commit any pending local changes (the engine already auto-commits writes),
 *  2. fetch `refs/svod/sync/<vaultId>`,
 *  3. up-to-date → done; remote ahead → fast-forward; local ahead → push;
 *     diverged → 3-way merge (clean → merge commit; real conflict → surface, leave local untouched),
 *  4. push `HEAD:refs/svod/sync/<vaultId>` (non-force; reject → re-fetch/merge, bounded retry).
 *
 * **Conflicts are never silently resolved.** A clean merge (incl. structural YAML frontmatter and
 * line-level body merges, via [FrontmatterMerge]) commits automatically. A real overlap (or
 * modify/delete) is recorded in [ConflictStore] with base/ours/theirs and the local tree is left
 * untouched; the user resolves via POST /conflicts/resolve. The answer is stored durably against
 * the incoming blob it was given for, and the next cycle's merge applies it and creates the merge
 * commit with the incoming head as a parent, so that version counts as absorbed. An open conflict
 * does not stop the cycle: every cycle fetches and re-plans, so a peer that moves on or converges
 * updates (or clears) the conflict. Nothing is ever lost — full history on every machine, and an
 * incoming file that trips the secret scanner is quarantined, never written unless the user
 * accepts it explicitly.
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

    /** Sync status surfaced to the API/UI. */
    enum class Status { inSync, syncing, conflicts, offline, error }

    data class Result(val status: Status, val head: String?, val conflicts: Int, val lastSyncedAt: String?)

    @Volatile
    var lastResult: Result? = null
        private set

    /** Step of the cycle in progress (`commit`, `fetch`, `merge`, `push`), null when idle. */
    @Volatile
    var phase: String? = null
        private set

    private fun phase(p: String) {
        phase = p
        eventBus.publish(EventTypes.SYNC_PROGRESS) { put("vault", vaultId); put("phase", p) }
    }

    private fun record(status: Status, head: String?, syncedAt: String? = lastResult?.lastSyncedAt): Result {
        if (status == Status.inSync) conflicts.retainOnly(emptySet()) // reconciled → nothing is open any more
        return Result(status, head, conflicts.all().size, syncedAt).also { lastResult = it }
    }

    /** Head last mirrored to the browsable `main` branch; skip re-pushing an unchanged head. */
    @Volatile
    private var lastMirroredHead: String? = null

    /**
     * Keep a browsable `main` branch tracking [head] on [remote] (force-push) so the vault shows up
     * in GitHub's web UI / GitFox, which list only heads+tags — never the `refs/svod/sync/…` ref the
     * vault actually rides on. Only pushes when [head] changed since the last mirror, so an idle
     * in-sync cycle doesn't re-push every tick. Best-effort/cosmetic — a failure is swallowed and
     * simply retried next cycle; the canonical sync ref remains the source of truth.
     */
    private fun mirror(remote: String, head: String?) {
        if (head == null || head == lastMirroredHead) return
        if (git.mirrorToBrowsableBranch(remote, branch)) lastMirroredHead = head
    }

    /** Run one reconcile cycle against [remote] (a URL or a Secrets ref). Never throws. */
    suspend fun sync(remote: String): Result = mutex.withLock {
        try { runCycle(remote) } finally { phase = null }
    }

    private suspend fun runCycle(remote: String): Result {
        val resolved = try { Secrets.resolve(remote) } catch (_: Exception) { remote }
        // 1. Fold any out-of-band working-tree edits into history first (engine writes already commit).
        phase("commit")
        runCatching { engine.ingestExternalChanges(author) }

        var attempt = 0
        val maxAttempts = 5
        while (true) {
            // 2. Fetch the canonical head (missing ref = nobody has pushed yet, not an error).
            phase("fetch")
            try { git.fetchSync(resolved, vaultId) } catch (_: Exception) { return record(Status.offline, engine.head()) }
            val remoteHead = git.syncRef(vaultId)
            // The snapshot this round reconciles, pinned once the fetch is in: everything below plans
            // against it, moves the ref only if HEAD still equals it, and pushes exactly it.
            val local = engine.head() ?: return record(Status.error, null)

            val toPush: String = when {
                remoteHead == null -> local                             // first push: create the canonical ref
                local == remoteHead -> { mirror(resolved, local); return record(Status.inSync, local, Instant.now().toString()) }
                git.isAncestor(local, remoteHead) -> {                  // remote ahead → fast-forward
                    phase("merge")
                    if (!engine.fastForwardTo(remoteHead, expectedHead = local)) continue // local moved → re-plan
                    mirror(resolved, remoteHead)
                    return record(Status.inSync, remoteHead, Instant.now().toString())
                }
                git.isAncestor(remoteHead, local) -> local              // local ahead → push the snapshot
                else -> {
                    phase("merge")
                    when (val m = merge(local, remoteHead)) {           // diverged → 3-way merge
                        MergeStep.Aborted -> continue                   // local moved during apply → re-plan
                        MergeStep.Conflicts -> return record(Status.conflicts, engine.head())
                        is MergeStep.Merged -> m.commit                 // clean merge commit created → push it
                    }
                }
            }

            phase("push")
            when (git.pushSync(resolved, toPush, vaultId)) {
                SyncGit.PushResult.OK -> {
                    mirror(resolved, toPush)
                    eventBus.publish(EventTypes.INDEX_UPDATED) { put("vault", vaultId); put("syncHead", toPush) }
                    return record(Status.inSync, toPush, Instant.now().toString())
                }
                SyncGit.PushResult.REJECTED -> {                        // a peer pushed first → re-fetch/merge
                    if (++attempt >= maxAttempts) return record(Status.error, engine.head())
                    delay(backoffMillis(attempt))
                }
                SyncGit.PushResult.ERROR -> return record(Status.offline, engine.head())
            }
        }
    }

    private sealed interface MergeStep { data class Merged(val commit: String) : MergeStep; object Conflicts : MergeStep; object Aborted : MergeStep }

    /**
     * Diverged 3-way merge of [theirs] into [ours]. Clean → a merge commit (parents ours+theirs),
     * applying the user's recorded answers; any open conflict → record base/ours/theirs and leave
     * the local tree untouched until the user answers it.
     */
    private suspend fun merge(ours: String, theirs: String): MergeStep {
        val base = git.mergeBase(ours, theirs)
        val plan = plan(base, ours, theirs)
        if (plan.conflicts.isNotEmpty()) {
            val before = conflicts.all().map { it.path }.toSet()
            conflicts.retainOnly(plan.conflicts.map { it.path }.toSet())
            plan.conflicts.forEach { conflicts.record(it.path, it.base, it.ours, it.theirs, it.reasons, it.theirsBlob, it.quarantined) }
            if (conflicts.all().map { it.path }.toSet() != before) {
                eventBus.publish(EventTypes.CONFLICT) { put("source", "sync"); put("vault", vaultId); put("count", conflicts.all().size) }
            }
            return MergeStep.Conflicts
        }
        val msg = buildString {
            append("sync: merge ${theirs.take(8)} into ${ours.take(8)} on $hostId")
            if (plan.resolved.isNotEmpty()) {
                append("\n")
                for ((path, choice) in plan.resolved) append("\nresolved $path: ${if (choice == ConflictStore.Choice.incoming) "accepted incoming" else "kept local"}")
            }
            if (plan.overrides.isNotEmpty()) {
                append("\n")
                for ((path, findings) in plan.overrides) append("\nsecret scan overridden by the user for $path: ${findings.joinToString(", ")}")
            }
        }
        val applied = engine.applyMerge(plan.writes, plan.deletes, theirs, msg, author, expectedHead = ours)
            ?: return MergeStep.Aborted
        conflicts.retainOnly(emptySet())
        conflicts.consumed(plan.resolved.keys)
        return MergeStep.Merged(applied)
    }

    private data class Conflict(
        val path: String, val base: String?, val ours: String?, val theirs: String?, val reasons: List<String>,
        val theirsBlob: String?, val quarantined: Boolean = false,
    )
    private data class Plan(
        val writes: Map<String, String>,
        val deletes: List<String>,
        val conflicts: List<Conflict>,
        /** Paths settled by a recorded answer, folded in by this merge. */
        val resolved: Map<String, ConflictStore.Choice>,
        /** Quarantined paths the user accepted anyway → the scan findings they overrode. */
        val overrides: Map<String, List<String>>,
    )

    /**
     * File-by-file 3-way between [base], [ours], [theirs]. A path the user already answered for this
     * exact incoming blob takes that answer: `ours` keeps the local file (or its absence), `incoming`
     * takes theirs without the secret scan (the user saw the findings and chose to). Incoming content
     * that trips the secret scanner otherwise is quarantined as a conflict, never written.
     */
    private fun plan(base: String?, ours: String, theirs: String): Plan {
        val baseFiles = base?.let { git.filesAt(it) } ?: emptyMap()
        val ourFiles = git.filesAt(ours)
        val theirFiles = git.filesAt(theirs)

        val writes = LinkedHashMap<String, String>()
        val deletes = ArrayList<String>()
        val open = ArrayList<Conflict>()
        val resolved = LinkedHashMap<String, ConflictStore.Choice>()
        val overrides = LinkedHashMap<String, List<String>>()

        fun readBase(path: String) = baseFiles[path]?.let { git.read(base!!, path) }
        fun takeTheirs(path: String, tb: String?) { if (tb == null) deletes.add(path) else writes[path] = git.read(theirs, path)!! }
        /** Stage incoming [content] unless it trips the secret scanner — then quarantine it, or note the user's override. */
        fun takeIncoming(path: String, content: String, tb: String, accepted: Boolean) {
            val findings = engine.scanSecrets(content)
            when {
                findings.isEmpty() -> writes[path] = content
                accepted -> { writes[path] = content; overrides[path] = findings }
                else -> open.add(Conflict(path, readBase(path), ourFiles[path]?.let { git.read(ours, path) }, content,
                    listOf("incoming file quarantined — secret(s) detected: ${findings.joinToString(", ")}"), tb, quarantined = true))
            }
        }

        for (path in (ourFiles.keys + theirFiles.keys + baseFiles.keys)) {
            val ob = ourFiles[path]; val tb = theirFiles[path]; val bb = baseFiles[path]
            if (ob == tb || tb == bb) continue                          // identical on both sides / theirs unchanged → keep ours
            val answer = conflicts.resolution(path, tb)
            if (answer != null) resolved[path] = answer
            when {
                answer == ConflictStore.Choice.ours -> {}               // the user's resolution / "keep mine" wins
                ob == bb ->                                             // ours unchanged → take theirs
                    if (tb == null) deletes.add(path) else takeIncoming(path, git.read(theirs, path)!!, tb, accepted = answer != null)
                answer == ConflictStore.Choice.incoming -> takeTheirs(path, tb)
                ob == null || tb == null ->                            // modify/delete (or rename/edit) → real conflict
                    open.add(Conflict(path, readBase(path), ob?.let { git.read(ours, path) }, tb?.let { git.read(theirs, path) },
                        listOf("file removed on one machine and modified on another"), tb))
                path.endsWith(".md") -> when (val out = FrontmatterMerge.merge(readBase(path), git.read(ours, path)!!, git.read(theirs, path)!!)) {
                    is FrontmatterMerge.Outcome.Merged -> takeIncoming(path, out.content, tb, accepted = false)
                    is FrontmatterMerge.Outcome.Conflict -> open.add(Conflict(path, out.base, out.ours, out.theirs, out.reasons, tb))
                }
                else -> open.add(Conflict(path, readBase(path), git.read(ours, path), git.read(theirs, path),
                    listOf("binary/non-markdown file changed on both machines"), tb))
            }
        }
        return Plan(writes, deletes, open, resolved, overrides)
    }

    private fun backoffMillis(attempt: Int): Long = (50L shl (attempt - 1)).coerceAtMost(800L)
}
