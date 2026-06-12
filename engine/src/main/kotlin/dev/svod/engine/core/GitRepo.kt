package dev.svod.engine.core

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Thin durability layer over jgit. The working tree is the live state; git is the
 * append-only history that guarantees a "lost" file is always recoverable.
 *
 * Not thread-safe by design: every method is invoked exclusively from the single
 * write-actor thread, which serializes all access.
 */
class GitRepo private constructor(
    private val repo: Repository,
    private val git: Git,
) : AutoCloseable {

    /**
     * Content-addressed blob id of [bytes] — identical to `git hash-object`. Computed
     * without writing anything, so it is safe to call for revision comparisons.
     */
    fun blobId(bytes: ByteArray): Revision =
        repo.newObjectInserter().use { it.idFor(Constants.OBJ_BLOB, bytes).name() }

    fun headId(): String? = repo.resolve(Constants.HEAD)?.name

    /**
     * Stage the entire working tree (additions, modifications and deletions) and commit
     * with [author] as both author and committer. Returns the new commit id, or the
     * current HEAD if there was nothing to commit. Temp files and `.svod/` are ignored
     * via `.gitignore`, so they are never staged.
     */
    fun commitAll(message: String, author: Author): String? {
        git.add().addFilepattern(".").call()                 // new + modified
        git.add().setUpdate(true).addFilepattern(".").call() // deletions of tracked paths

        val status = git.status().call()
        val nothingStaged = status.added.isEmpty() && status.changed.isEmpty() && status.removed.isEmpty()
        if (nothingStaged) return headId()

        val ident = PersonIdent(author.name, author.email)
        val commit = git.commit()
            .setAuthor(ident)
            .setCommitter(ident)
            .setMessage(message)
            .setSign(false)
            .call()
        return commit.name
    }

    /** True if the working tree differs from the index/HEAD in any tracked way. */
    fun isClean(): Boolean = git.status().call().isClean

    /** History for a single path (most recent first), or vault-wide when [path] is null. */
    fun log(path: String?, max: Int): List<CommitInfo> {
        if (headId() == null) return emptyList()
        val cmd = git.log().setMaxCount(max)
        if (path != null) cmd.addPath(path)
        return cmd.call().map { c ->
            CommitInfo(
                commit = c.name,
                authorName = c.authorIdent.name,
                authorEmail = c.authorIdent.emailAddress,
                epochSeconds = c.authorIdent.whenAsInstant.epochSecond,
                message = c.fullMessage.trim(),
            )
        }
    }

    /** Content of [path] as it existed at [revision] (a commit id, ref, or "HEAD"). */
    fun readAtRevision(path: String, revision: String): ByteArray? {
        val id = repo.resolve(revision) ?: return null
        RevWalk(repo).use { rw ->
            val tree = rw.parseCommit(id).tree
            TreeWalk.forPath(repo, path, tree)?.use { tw -> return repo.open(tw.getObjectId(0)).bytes }
        }
        return null
    }

    /** Unified diff of [path] between two revisions (commit ids/refs). Empty if unchanged. */
    fun diff(path: String, fromRevision: String, toRevision: String): String {
        val from = repo.resolve(fromRevision) ?: throw IllegalArgumentException("unknown revision: $fromRevision")
        val to = repo.resolve(toRevision) ?: throw IllegalArgumentException("unknown revision: $toRevision")
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { df ->
            df.setRepository(repo)
            df.pathFilter = PathFilter.create(path)
            df.format(treeParser(from), treeParser(to))
        }
        return out.toString(StandardCharsets.UTF_8)
    }

    private fun treeParser(commit: ObjectId): CanonicalTreeParser {
        val tree = RevWalk(repo).use { it.parseCommit(commit).tree }
        return CanonicalTreeParser().also { p ->
            repo.newObjectReader().use { reader -> p.reset(reader, tree) }
        }
    }

    override fun close() {
        git.close()
        repo.close()
    }

    companion object {
        fun openOrInit(root: Path): GitRepo {
            val gitDir = root.resolve(".git").toFile()
            val existed = gitDir.isDirectory
            val repo = FileRepositoryBuilder()
                .setGitDir(gitDir)
                .setWorkTree(root.toFile())
                .build()
            if (!existed) repo.create(false)

            repo.config.apply {
                setBoolean("core", null, "quotepath", false) // raw UTF-8 paths (Cyrillic)
                setBoolean("core", null, "autocrlf", false)   // never rewrite bytes under us
                setString("i18n", null, "commitEncoding", "UTF-8")
                setString("i18n", null, "logOutputEncoding", "UTF-8")
                save()
            }
            return GitRepo(repo, Git(repo))
        }
    }
}
