package dev.svod.engine.index

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.AndTreeFilter
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.nio.file.Path

/**
 * Read-only view of the vault's git history for the indexer.
 *
 * This holds its OWN jgit [Repository] handle, separate from the engine's write-path
 * `GitRepo`. Git allows many concurrent readers alongside the single writer, so the
 * indexer can read committed blobs and diffs on its own thread without touching — or
 * being blocked by — the write actor. The index is built from committed state only,
 * never the working tree, which is what makes it an exact function of git HEAD.
 */
class GitReader(root: Path) : AutoCloseable {

    private val repo: Repository = FileRepositoryBuilder()
        .setGitDir(root.resolve(".git").toFile())
        .setWorkTree(root.toFile())
        .build()

    data class Change(val path: String, val type: DiffEntry.ChangeType, val newPath: String)

    fun headCommit(): String? = repo.resolve(Constants.HEAD)?.name

    /** Markdown blobs reachable from [commit], as path → blob id (excludes engine dirs). */
    fun listMarkdown(commit: String): Map<String, String> {
        val tree = RevWalk(repo).use { it.parseCommit(ObjectId.fromString(commit)).tree }
        val out = LinkedHashMap<String, String>()
        TreeWalk(repo).use { tw ->
            tw.addTree(tree)
            tw.isRecursive = true
            tw.filter = PathSuffixFilter.create(".md")
            while (tw.next()) {
                val path = tw.pathString
                if (isIndexable(path)) out[path] = tw.getObjectId(0).name
            }
        }
        return out
    }

    fun readBlob(commit: String, path: String): ByteArray? {
        val tree = RevWalk(repo).use { it.parseCommit(ObjectId.fromString(commit)).tree }
        TreeWalk.forPath(repo, path, tree)?.use { tw ->
            return repo.open(tw.getObjectId(0)).bytes
        }
        return null
    }

    /** Bytes of the blob [blobId] (e.g. a value from [listMarkdown]), without resolving a commit or path. */
    fun readBlobById(blobId: String): ByteArray = repo.open(ObjectId.fromString(blobId)).bytes

    /** Git blob id of [path] at [commit] (the file-level revision used to detect changes). */
    fun blobId(commit: String, path: String): String? {
        val tree = RevWalk(repo).use { it.parseCommit(ObjectId.fromString(commit)).tree }
        TreeWalk.forPath(repo, path, tree)?.use { tw ->
            return tw.getObjectId(0).name
        }
        return null
    }

    fun commitEpochSeconds(commit: String): Long =
        RevWalk(repo).use { it.parseCommit(ObjectId.fromString(commit)).commitTime.toLong() }

    /**
     * The last commit on HEAD's first-parent line whose commit time is at or before [epochSeconds],
     * or null when there is none (empty repo, or the time precedes the first commit). Commits that
     * came in through a merge count from the time the merge was made.
     */
    fun commitAtOrBefore(epochSeconds: Long): String? {
        val head = repo.resolve(Constants.HEAD) ?: return null
        RevWalk(repo).use { walk ->
            var c: RevCommit? = walk.parseCommit(head)
            while (c != null) {
                if (c.commitTime.toLong() <= epochSeconds) return c.name
                c = if (c.parentCount > 0) walk.parseCommit(c.getParent(0)) else null
            }
        }
        return null
    }

    /** The latest commit reachable from [commit] that touched [path], as (commit id, author name), or null. */
    fun lastCommitTouching(commit: String, path: String): Pair<String, String>? {
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(ObjectId.fromString(commit)))
            walk.setTreeFilter(AndTreeFilter.create(PathFilter.create(path), TreeFilter.ANY_DIFF))
            val c = walk.next() ?: return null
            return c.name to c.authorIdent.name
        }
    }

    /** Changed markdown paths between two commits (for incremental indexing from commits). */
    fun diffMarkdown(oldCommit: String, newCommit: String): List<Change> {
        val oldTree = parser(oldCommit)
        val newTree = parser(newCommit)
        val out = ArrayList<Change>()
        DiffFormatter(DisabledOutputStream.INSTANCE).use { df ->
            df.setRepository(repo)
            for (e in df.scan(oldTree, newTree)) {
                val path = if (e.changeType == DiffEntry.ChangeType.DELETE) e.oldPath else e.newPath
                val newPath = e.newPath
                if (path.endsWith(".md") && isIndexable(path)) out.add(Change(path, e.changeType, newPath))
            }
        }
        return out
    }

    private fun parser(commit: String): CanonicalTreeParser {
        val tree = RevWalk(repo).use { it.parseCommit(ObjectId.fromString(commit)).tree }
        return CanonicalTreeParser().also { p ->
            repo.newObjectReader().use { reader -> p.reset(reader, tree) }
        }
    }

    private fun isIndexable(path: String): Boolean {
        val top = path.substringBefore('/')
        return top != ".trash" && top != ".svod" && top != ".git" && !path.startsWith(".")
    }

    override fun close() = repo.close()
}
