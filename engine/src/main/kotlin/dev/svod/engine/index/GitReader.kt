package dev.svod.engine.index

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter
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
