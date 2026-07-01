package dev.svod.engine.sync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.treewalk.TreeWalk
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path

/**
 * Read-only + network git access for sync: fetch/push over a remote, plus the ancestry and
 * tree reads the merge needs. Holds its OWN jgit handle (separate from the write-actor's
 * `GitRepo`); fetch writes only remote-tracking refs and push only reads, so neither races
 * the single writer. All ref-moving WRITES go back through the engine actor.
 *
 * Peer branches are fetched into the `refs/svodremote/` namespace so they never collide with
 * local refs.
 */
class SyncGit(root: Path) : AutoCloseable {

    private val repo: Repository = FileRepositoryBuilder()
        .setGitDir(root.resolve(".git").toFile())
        .setWorkTree(root.toFile())
        .build()
    private val git = Git(repo)

    val branch: String get() = repo.branch

    fun fetch(remote: String) {
        git.fetch()
            .setRemote(remote)
            .setRefSpecs(RefSpec("+refs/heads/*:refs/svodremote/*"))
            .call()
    }

    /**
     * Fetch the canonical sync ref `refs/svod/sync/<vaultId>` into the local tracking ref
     * `refs/svodremote/sync/<vaultId>`. Force (`+`) only updates a tracking ref, never the vault.
     * A missing remote ref (no machine has pushed yet) is not an error — the tracking ref stays
     * absent and [syncRef] returns null.
     */
    fun fetchSync(remote: String, vaultId: String) {
        // A wildcard refspec matches zero refs without error when no machine has pushed the
        // canonical ref yet (first machine bootstrapping); a real transport failure still throws.
        git.fetch()
            .setRemote(remote)
            .setRefSpecs(RefSpec("+refs/svod/sync/*:refs/svodremote/sync/*"))
            .call()
    }

    /** The canonical sync head for [vaultId] as last fetched (null until a peer has pushed it). */
    fun syncRef(vaultId: String): String? = repo.resolve("refs/svodremote/sync/$vaultId")?.name

    /** Outcome of a canonical-ref push: pushed cleanly, rejected (a peer advanced it first), or a transport error. */
    enum class PushResult { OK, REJECTED, ERROR }

    /**
     * Push the local [branch] to the canonical sync ref `refs/svod/sync/<vaultId>` on [remote],
     * **non-force**: a non-fast-forward (another machine pushed in between) is [PushResult.REJECTED]
     * so the caller re-fetches/re-merges and retries. A transport failure is [PushResult.ERROR].
     */
    fun pushSync(remote: String, branch: String, vaultId: String): PushResult = try {
        val results = git.push().setRemote(remote)
            .setRefSpecs(RefSpec("refs/heads/$branch:refs/svod/sync/$vaultId"))
            .call()
        var ok = true
        for (result in results) for (update in result.remoteUpdates) {
            when (update.status) {
                RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> {}
                else -> ok = false
            }
        }
        if (ok) PushResult.OK else PushResult.REJECTED
    } catch (_: Exception) {
        PushResult.ERROR
    }

    /**
     * Best-effort mirror of the local [branch] to a browsable branch `refs/heads/main` on [remote],
     * force-updated. GitHub's web UI and desktop git clients (GitFox, etc.) list only heads + tags —
     * never the svod sync/backup refs (`refs/svod/sync/…`, `refs/svod/backup/…`) a vault actually
     * rides on — so without this a fully backed-up vault looks empty in the browser. Purely cosmetic
     * visibility: a failure is swallowed so it can never wedge or fail a cycle (the svod ref is truth).
     */
    fun mirrorToBrowsableBranch(remote: String, branch: String): Boolean = try {
        push(remote, "+refs/heads/$branch:refs/heads/main")
    } catch (_: Exception) {
        false
    }

    /** Push [refspec] to [remote]; true if every update was OK/UP_TO_DATE (not rejected). */
    fun push(remote: String, refspec: String): Boolean {
        val results = git.push().setRemote(remote).setRefSpecs(RefSpec(refspec)).call()
        for (result in results) {
            for (update in result.remoteUpdates) {
                when (update.status) {
                    RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> {}
                    else -> return false
                }
            }
        }
        return true
    }

    fun resolve(rev: String): String? = repo.resolve(rev)?.name

    /** Object id of a peer branch fetched under refs/svodremote/, or null. */
    fun remoteRef(branch: String): String? = repo.resolve("refs/svodremote/$branch")?.name

    /** Peer branches under refs/svodremote/<prefix>, as shortName → object id. */
    fun listRemoteBranches(prefix: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (ref in repo.refDatabase.getRefsByPrefix("refs/svodremote/$prefix")) {
            out[ref.name.removePrefix("refs/svodremote/")] = ref.objectId.name
        }
        return out
    }

    fun isAncestor(maybeAncestor: String, descendant: String): Boolean = RevWalk(repo).use { rw ->
        rw.isMergedInto(rw.parseCommit(ObjectId.fromString(maybeAncestor)), rw.parseCommit(ObjectId.fromString(descendant)))
    }

    fun mergeBase(a: String, b: String): String? = RevWalk(repo).use { rw ->
        rw.revFilter = RevFilter.MERGE_BASE
        rw.markStart(rw.parseCommit(ObjectId.fromString(a)))
        rw.markStart(rw.parseCommit(ObjectId.fromString(b)))
        rw.next()?.name
    }

    /** Tracked files at [commit] as path → blob id. */
    fun filesAt(commit: String): Map<String, String> {
        val tree = RevWalk(repo).use { it.parseCommit(ObjectId.fromString(commit)).tree }
        val out = LinkedHashMap<String, String>()
        TreeWalk(repo).use { tw ->
            tw.addTree(tree)
            tw.isRecursive = true
            while (tw.next()) out[tw.pathString] = tw.getObjectId(0).name
        }
        return out
    }

    fun read(commit: String, path: String): String? {
        val tree = RevWalk(repo).use { it.parseCommit(ObjectId.fromString(commit)).tree }
        TreeWalk.forPath(repo, path, tree)?.use { return String(repo.open(it.getObjectId(0)).bytes, UTF_8) }
        return null
    }

    override fun close() {
        git.close()
        repo.close()
    }
}
