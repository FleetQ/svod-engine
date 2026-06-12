package dev.svod.engine.sync

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.put

/**
 * Multi-host replication over git. Topology: a shared remote with a canonical branch; each
 * host writes locally (single-writer, offline-capable) and reconciles via fetch/merge/push.
 *
 * **Determinism via a designated merge authority.** Only the authority creates merge commits.
 * A replica that diverges does NOT merge: it pushes its commits to a proposal branch
 * (`svod/<hostId>`) and fast-forwards once the authority has folded them into the canonical
 * branch. This keeps merge results identical across the fleet.
 *
 * **Conflicts are surfaced, never silently resolved.** Cleanly-mergeable files (including
 * structural YAML frontmatter, via [FrontmatterMerge]) are merged; a true conflict keeps
 * *ours* in the tree (theirs is preserved in history) and is recorded in [ConflictStore] for
 * a human/agent to resolve. Nothing is ever lost — every host holds the full history.
 *
 * All ref-moving writes go through the engine's write-actor, so the Step-1 single-writer
 * guarantees hold during sync.
 */
class SyncEngine(
    private val engine: SvodEngine,
    private val git: SyncGit,
    private val conflicts: ConflictStore,
    private val eventBus: EventBus,
    private val hostId: String,
    private val isMergeAuthority: Boolean,
    private val remote: String,
) {
    private val branch = engine.branch()
    private val mutex = Mutex()
    private val author = Author("svod-sync", "sync@svod.localhost")

    data class Result(val head: String?, val conflicts: Int, val role: String)

    suspend fun sync(): Result = mutex.withLock {
        git.fetch(remote)

        git.remoteRef(branch)?.let { remoteMain -> reconcile(remoteMain) }
        if (isMergeAuthority) mergeProposals()
        pushLocal()

        Result(engine.head(), conflicts.all().size, if (isMergeAuthority) "authority" else "replica")
    }

    private suspend fun reconcile(remoteMain: String) {
        val local = engine.head() ?: return
        if (local == remoteMain) return
        if (git.isAncestor(local, remoteMain)) {
            engine.fastForwardTo(remoteMain) // remote ahead → pull
            return
        }
        if (git.isAncestor(remoteMain, local)) return // local ahead → pushLocal handles it
        // diverged
        if (isMergeAuthority) {
            mergeInto(remoteMain)
        } else {
            git.push(remote, "+refs/heads/$branch:refs/heads/svod/$hostId") // propose; wait for authority
        }
    }

    private suspend fun mergeProposals() {
        for ((name, commit) in git.listRemoteBranches("svod/")) {
            if (name == "svod/$hostId") continue
            val local = engine.head() ?: continue
            if (git.isAncestor(commit, local)) continue // already folded in
            mergeInto(commit)
        }
    }

    /** Authority: merge [theirs] into local, file by file, surfacing conflicts. */
    private suspend fun mergeInto(theirs: String) {
        val ours = engine.head() ?: return
        if (git.isAncestor(theirs, ours)) return
        if (git.isAncestor(ours, theirs)) { engine.fastForwardTo(theirs); return }

        val base = git.mergeBase(ours, theirs)
        val baseFiles = base?.let { git.filesAt(it) } ?: emptyMap()
        val ourFiles = git.filesAt(ours)
        val theirFiles = git.filesAt(theirs)

        val writes = LinkedHashMap<String, String>()
        val deletes = ArrayList<String>()
        var newConflicts = 0

        for (path in (ourFiles.keys + theirFiles.keys + baseFiles.keys)) {
            val ob = ourFiles[path]
            val tb = theirFiles[path]
            val bb = baseFiles[path]
            when {
                ob == tb -> {}                       // identical on both sides
                tb == bb -> {}                        // theirs unchanged → keep ours
                ob == bb -> if (tb == null) deletes.add(path) else writes[path] = git.read(theirs, path)!! // take theirs
                ob == null || tb == null -> {         // deleted one side, modified the other
                    conflicts.record(path, bb?.let { git.read(base!!, path) }, ob?.let { git.read(ours, path) }, tb?.let { git.read(theirs, path) },
                        listOf("file removed on one host and modified on another"))
                    newConflicts++
                }
                path.endsWith(".md") -> when (val out = FrontmatterMerge.merge(bb?.let { git.read(base!!, path) }, git.read(ours, path)!!, git.read(theirs, path)!!)) {
                    is FrontmatterMerge.Outcome.Merged -> writes[path] = out.content
                    is FrontmatterMerge.Outcome.Conflict -> { conflicts.record(path, out.base, out.ours, out.theirs, out.reasons); newConflicts++ }
                }
                else -> {                              // non-markdown changed on both → can't structurally merge
                    conflicts.record(path, bb?.let { git.read(base!!, path) }, git.read(ours, path), git.read(theirs, path), listOf("binary/non-markdown file changed on both hosts"))
                    newConflicts++
                }
            }
        }

        val message = buildString {
            append("sync: merge ${theirs.take(8)} into ${ours.take(8)}")
            if (newConflicts > 0) append(" (+$newConflicts conflict(s) surfaced)")
        }
        engine.applyMerge(writes, deletes, theirs, message, author)

        if (newConflicts > 0) eventBus.publish(EventTypes.CONFLICT) { put("source", "sync"); put("count", conflicts.all().size) }
    }

    private suspend fun pushLocal() {
        val local = engine.head() ?: return
        val remoteMain = git.remoteRef(branch)
        when {
            remoteMain == local -> {}
            remoteMain == null || git.isAncestor(remoteMain, local) ->
                git.push(remote, "refs/heads/$branch:refs/heads/$branch") // fast-forward push of the canonical branch
            !isMergeAuthority ->
                git.push(remote, "+refs/heads/$branch:refs/heads/svod/$hostId") // replica still diverged → keep proposing
        }
    }
}
