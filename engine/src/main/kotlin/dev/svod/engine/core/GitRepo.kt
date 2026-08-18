package dev.svod.engine.core

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
    /**
     * Identity recorded as the git *committer* on every commit (the *author* stays the caller —
     * the agent/UI that made the change). On a synced machine this is the host id, so history and
     * the conflict UI can show "edited on machineA vs machineB". Null ⇒ committer = author.
     */
    private val committer: Author? = null,
) : AutoCloseable {

    private fun committerIdent(author: Author): PersonIdent =
        committer?.let { PersonIdent(it.name, it.email) } ?: PersonIdent(author.name, author.email)

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

        val commit = git.commit()
            .setAuthor(PersonIdent(author.name, author.email))
            .setCommitter(committerIdent(author))
            .setMessage(message)
            .setSign(false)
            .call()
        return commit.name
    }

    /**
     * Commit exactly [paths] (additions, modifications, and deletions OF those paths). Returns the
     * new commit id, or the current HEAD when [paths] introduced nothing to commit.
     *
     * Unlike [commitAll], the cost is O(changed paths), NOT O(working tree). The high-level
     * `git add` / `git status` (what [commitAll] uses) both run a `FileTreeIterator` that **stats
     * every tracked file** regardless of any path filter — ~37s on a 70k-file vault, paid by every
     * write/delete/sync. Here we edit the index (DirCache) in memory: read only the changed files,
     * splice their blobs into the existing index, write the tree, and move HEAD — no working-tree
     * walk at all. `.gitignore` is irrelevant because callers only pass real vault paths (the same
     * paths they just wrote); `.svod/` and temp files are never among them.
     */
    fun commitPaths(paths: Collection<String>, message: String, author: Author): String? {
        val distinct = paths.toSet()
        if (distinct.isEmpty()) return headId()
        val workTree = repo.workTree.toPath()
        val head: ObjectId? = repo.resolve(Constants.HEAD)
        val dirCache = repo.lockDirCache()
        var locked = true
        try {
            val editor = dirCache.editor()
            val commitId = repo.newObjectInserter().use { inserter ->
                var changed = false
                for (p in distinct) {
                    val f = workTree.resolve(p)
                    if (Files.isRegularFile(f)) {
                        val bytes = Files.readAllBytes(f)
                        val blob = inserter.insert(Constants.OBJ_BLOB, bytes)
                        if (dirCache.getEntry(p)?.objectId != blob) {
                            val mtime = Files.getLastModifiedTime(f).toInstant()
                            editor.add(object : DirCacheEditor.PathEdit(p) {
                                override fun apply(ent: DirCacheEntry) {
                                    ent.fileMode = FileMode.REGULAR_FILE
                                    ent.setObjectId(blob)
                                    ent.setLength(bytes.size.toLong())
                                    ent.setLastModified(mtime)
                                }
                            })
                            changed = true
                        }
                    } else if (dirCache.getEntry(p) != null) {
                        editor.add(DirCacheEditor.DeletePath(p)); changed = true
                    }
                }
                if (!changed) return@use null
                editor.finish()
                val treeId = dirCache.writeTree(inserter)
                val cb = CommitBuilder().apply {
                    setTreeId(treeId)
                    if (head != null) setParentId(head)
                    setAuthor(PersonIdent(author.name, author.email))
                    setCommitter(committerIdent(author))
                    setMessage(message)
                }
                inserter.insert(cb).also { inserter.flush() }
            }
            if (commitId == null) return headId()
            dirCache.write()                                    // serialize entries to the .lock file …
            if (!dirCache.commit()) throw IllegalStateException("could not write index for: $message") // … then rename it onto .git/index
            locked = false
            val ru = repo.updateRef(Constants.HEAD)
            ru.setNewObjectId(commitId)
            head?.let { ru.setExpectedOldObjectId(it) }
            ru.setRefLogMessage("commit: $message", false)
            ru.update()
            return commitId.name
        } finally {
            if (locked) runCatching { dirCache.unlock() }
        }
    }

    /** True if the working tree differs from the index/HEAD in any tracked way. */
    fun isClean(): Boolean = git.status().call().isClean

    /** Current branch name (e.g. "master"). */
    fun branch(): String = repo.branch

    fun resolveRev(rev: String): String? = repo.resolve(rev)?.name

    /** Fast-forward the current branch to [commit] (hard) — moves the ref + working tree. */
    fun resetHardTo(commit: String) {
        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(commit).call()
    }

    /**
     * Commit the current working tree as a MERGE commit with parents [HEAD, theirs].
     * Caller has already written the merged file contents into the working tree.
     */
    fun commitMerge(message: String, author: Author, theirs: String): String {
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()
        repo.writeMergeHeads(listOf(ObjectId.fromString(theirs)))
        repo.writeMergeCommitMsg(message)
        return git.commit()
            .setAuthor(PersonIdent(author.name, author.email))
            .setCommitter(committerIdent(author))
            .setMessage(message)
            .call().name
    }

    /**
     * History for a single path (most recent first), or vault-wide when [path] is null,
     * capped at [max] commits.
     *
     * Uses native `git log -n <max>` rather than jgit's [Git.log]: jgit's path-filtered
     * RevWalk re-diffs every commit's tree along the path and cannot use commit-graph /
     * changed-path bloom filters, so on a large vault it walks the whole ancestry — a
     * single call measured ~12.7s. Native git does the same path-scoped walk in <0.1s.
     * Renames are NOT followed (no `--follow`), matching the previous jgit behaviour.
     * Falls back to the jgit walk if the git subprocess can't be run.
     */
    fun log(path: String?, max: Int): List<CommitInfo> {
        if (headId() == null) return emptyList()
        return try {
            nativeLog(path, max)
        } catch (_: Exception) {
            jgitLog(path, max)
        }
    }

    /**
     * True only when native `git status --porcelain` DEFINITIVELY reports a clean working tree.
     *
     * Any doubt — git missing, non-zero exit, timeout — answers false, so the caller falls back to
     * doing the full work. The fail-safe direction is "do more", never "skip".
     *
     * Exists because jgit's `add`/`status` (what [commitAll] uses) run a `FileTreeIterator` that
     * stats every tracked file: measured 15.3 s at boot on a 3,370-note vault, versus **20 ms** for
     * the same question asked natively.
     */
    fun isDefinitelyClean(): Boolean = try {
        val proc = ProcessBuilder(GIT_BIN, "status", "--porcelain")
            .directory(repo.workTree)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = proc.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        if (!proc.waitFor(30, TimeUnit.SECONDS)) { proc.destroyForcibly(); false }
        else proc.exitValue() == 0 && out.isBlank()
    } catch (_: Exception) {
        false
    }

    private fun nativeLog(path: String?, max: Int): List<CommitInfo> {
        // %x1f (unit sep) between fields, %x1e (record sep) between commits — bytes that
        // never occur in commit metadata, so parsing survives multi-line messages.
        val args = mutableListOf(
            GIT_BIN, "log", "-n", max.toString(),
            "--format=%H%x1f%an%x1f%ae%x1f%at%x1f%B%x1e",
        )
        if (path != null) { args += "--"; args += path }
        val proc = ProcessBuilder(args)
            .directory(repo.workTree)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["GIT_LITERAL_PATHSPECS"] = "1" } // literal path match, no glob magic
            .start()
        val out = proc.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        if (!proc.waitFor(30, TimeUnit.SECONDS)) { proc.destroyForcibly(); throw IOException("git log timed out") }
        if (proc.exitValue() != 0) throw IOException("git log exited ${proc.exitValue()}")
        return out.split('')
            .asSequence()
            .map { it.trim('\n') }
            .filter { it.isNotEmpty() }
            .map { rec ->
                val f = rec.split('')
                CommitInfo(f[0], f[1], f[2], f[3].toLong(), f[4].trim())
            }
            .toList()
    }

    private fun jgitLog(path: String?, max: Int): List<CommitInfo> {
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
        /** macOS ships git at this stable path (Xcode CLT shim); avoids launchd PATH gaps. */
        private const val GIT_BIN = "/usr/bin/git"

        fun openOrInit(root: Path, committer: Author? = null): GitRepo {
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
            return GitRepo(repo, Git(repo), committer)
        }
    }
}
