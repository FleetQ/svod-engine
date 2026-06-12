package dev.svod.engine.core

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
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
