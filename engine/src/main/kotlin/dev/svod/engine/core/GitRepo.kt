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
import org.eclipse.jgit.lib.RefUpdate
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
/** Commit trailer naming the agent that acted for a person (ADR-0020). */
private const val AGENT_TRAILER = "Svod-Agent:"

/** Every line ending a caller could use; the trailer filter must see at least what any reader splits on. */
private val LINE_BREAK = Regex("\r\n|\r|\n")

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

    private fun agentTrailer(message: String): String? =
        message.trim().substringAfterLast("\n\n").split('\n')
            .firstOrNull { it.startsWith(AGENT_TRAILER) }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The `Svod-Agent` trailer is engine-owned: a line with that key arriving inside caller-built text
     * (a path with a newline ends up in "write: <path>") is dropped, so a client cannot fake whose
     * agent made a commit (ADR-0020). Messages without such a line are untouched byte for byte.
     */
    private fun withAgentTrailer(message: String, author: Author): String {
        val clean = if (!message.contains(AGENT_TRAILER, ignoreCase = true)) message
        else message.split(LINE_BREAK).filterNot { it.trimStart().startsWith(AGENT_TRAILER, ignoreCase = true) }.joinToString("\n")
        return if (author.agent == null) clean else "$clean\n\n$AGENT_TRAILER ${author.agent}"
    }

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
            .setMessage(withAgentTrailer(message, author))
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
                    setMessage(withAgentTrailer(message, author))
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

    /**
     * Fast-forward the current branch to [commit] as a compare-and-swap: the ref moves only if it
     * still points at [expectedOld] (a JGit [RefUpdate] with the expected old id), then the index and
     * working tree follow. False — nothing touched — when the ref moved meanwhile (e.g. a `git commit`
     * run by hand in the vault; engine writes are already excluded by the write-actor).
     */
    fun fastForwardTo(commit: String, expectedOld: String?): Boolean {
        val ru = repo.updateRef(Constants.HEAD)
        ru.setNewObjectId(ObjectId.fromString(commit))
        expectedOld?.let { ru.setExpectedOldObjectId(ObjectId.fromString(it)) }
        ru.setRefLogMessage("sync: fast-forward", false)
        when (ru.update()) {
            RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NEW, RefUpdate.Result.NO_CHANGE -> {}
            else -> return false
        }
        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(commit).call()
        return true
    }

    /**
     * Commit the current working tree as a MERGE commit with parents [expectedHead, theirs].
     * Caller has already written the merged file contents into the working tree. The branch moves
     * by compare-and-swap against [expectedHead] (current HEAD when null); null when it moved.
     */
    fun commitMerge(message: String, author: Author, theirs: String, expectedHead: String?): String? {
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()
        val ours = ObjectId.fromString(expectedHead ?: headId() ?: return null)
        val commitId = repo.newObjectInserter().use { inserter ->
            val cb = CommitBuilder().apply {
                setTreeId(repo.readDirCache().writeTree(inserter))
                setParentIds(ours, ObjectId.fromString(theirs))
                setAuthor(PersonIdent(author.name, author.email))
                setCommitter(committerIdent(author))
                setMessage(withAgentTrailer(message, author))
            }
            inserter.insert(cb).also { inserter.flush() }
        }
        val ru = repo.updateRef(Constants.HEAD)
        ru.setNewObjectId(commitId)
        ru.setExpectedOldObjectId(ours)
        ru.setRefLogMessage("commit (merge): $message", false)
        return when (ru.update()) {
            RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NEW -> commitId.name
            else -> null
        }
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
    /**
     * The commit that ADDED [path] (`git log --diff-filter=A -n 1`). A path deleted and re-added
     * reports the latest add. Renames are not followed. Null when the path was never added.
     */
    fun added(path: String): CommitInfo? {
        if (headId() == null) return null
        return try {
            nativeLog(path, 1, listOf("--diff-filter=A")).firstOrNull()
        } catch (_: Exception) {
            jgitAdded(path)
        }
    }

    private fun jgitAdded(path: String): CommitInfo? =
        RevWalk(repo).use { rw ->
            jgitLog(path, Int.MAX_VALUE).firstOrNull { ci ->
                val c = rw.parseCommit(ObjectId.fromString(ci.commit))
                c.parentCount == 0 || TreeWalk.forPath(repo, path, rw.parseCommit(c.getParent(0)).tree) == null
            }
        }

    /**
     * For each of [trashPaths], the original path and the commit time (ISO-8601) of the soft delete that put it
     * there, read from the `delete: <orig> -> <trashRel>` message [SvodEngine] writes. One walk from HEAD,
     * newest first, stopping once every path is found; a path with no such commit (moved in by hand, or a
     * shallow history) is simply absent.
     */
    fun trashOrigins(trashPaths: Set<String>): Map<String, Pair<String, String>> {
        val head = repo.resolve(Constants.HEAD) ?: return emptyMap()
        val out = HashMap<String, Pair<String, String>>()
        if (trashPaths.isEmpty()) return out
        RevWalk(repo).use { rw ->
            rw.markStart(rw.parseCommit(head))
            for (c in rw) {
                val msg = c.shortMessage
                if (!msg.startsWith("delete: ")) continue
                val arrow = msg.lastIndexOf(" -> ")
                if (arrow < 0) continue
                val trashRel = msg.substring(arrow + 4)
                if (trashRel in trashPaths && trashRel !in out) {
                    out[trashRel] = msg.substring("delete: ".length, arrow) to java.time.Instant.ofEpochSecond(c.commitTime.toLong()).toString()
                    if (out.size == trashPaths.size) break
                }
            }
        }
        return out
    }

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

    private fun nativeLog(path: String?, max: Int, extra: List<String> = emptyList()): List<CommitInfo> {
        // %x1f (unit sep) between fields, %x1e (record sep) between commits — bytes that
        // never occur in commit metadata, so parsing survives multi-line messages.
        val args = mutableListOf<String>(
            GIT_BIN, "log", "-n", max.toString(),
            "--format=%H%x1f%an%x1f%ae%x1f%at%x1f%B%x1e",
        ) .also { it.addAll(extra) }
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
                CommitInfo(f[0], f[1], f[2], f[3].toLong(), f[4].trim(), agentTrailer(f[4]))
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
                agent = agentTrailer(c.fullMessage),
            )
        }
    }

    /**
     * Vault-wide change feed, newest first (jgit only, no subprocess). Walks from HEAD — skipping through
     * [before] when given — back to [since]: a commit id/ref (that commit and its ancestors are left out) or an
     * ISO-8601 instant / date (start of that UTC day; the walk stops at the first older commit). At most [max]
     * commits; [ChangesPage.nextCursor] is the last returned commit when older ones remain. Each commit's paths
     * are diffed against its first parent with rename detection: a rename into `.trash/` is a DELETE, one out of
     * it a RESTORE. Unparsable [since] or unknown [before] throws [IllegalArgumentException].
     */
    fun changes(since: String?, before: String?, max: Int): ChangesPage {
        val sinceRaw = since?.trim()?.takeIf { it.isNotEmpty() }
        val beforeRaw = before?.trim()?.takeIf { it.isNotEmpty() }
        val sinceCommit = sinceRaw?.let { resolveCommitOrNull(it) }
        val sinceEpoch = if (sinceRaw != null && sinceCommit == null) {
            runCatching { java.time.Instant.parse(sinceRaw).epochSecond }
                .recoverCatching { java.time.LocalDate.parse(sinceRaw).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() }
                .getOrElse { throw IllegalArgumentException("since must be a commit id or an ISO-8601 instant or date, got '$sinceRaw'") }
        } else null
        val beforeCommit = beforeRaw?.let { resolveCommitOrNull(it) ?: throw IllegalArgumentException("unknown revision: $beforeRaw") }
        val head = repo.resolve(Constants.HEAD) ?: return ChangesPage(null, emptyList(), null)

        val out = ArrayList<ChangeEntry>()
        var more = false
        // Page by re-walking from HEAD and skipping through the cursor: starting at the cursor's parents would drop
        // the commits of a merged side branch that sort after the cursor but are not its ancestors. Only when the
        // cursor is no longer reachable from HEAD (history rewritten) does the walk fall back to its ancestry.
        var skipping = beforeCommit != null && RevWalk(repo).use { it.isMergedInto(it.parseCommit(beforeCommit), it.parseCommit(head)) }
        RevWalk(repo).use { rw ->
            if (beforeCommit != null && !skipping) {
                rw.parseCommit(beforeCommit).parents.forEach { rw.markStart(rw.parseCommit(it)) }
            } else {
                rw.markStart(rw.parseCommit(head))
            }
            sinceCommit?.let { rw.markUninteresting(rw.parseCommit(it)) }
            DiffFormatter(org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE).use { df ->
                df.setRepository(repo)
                df.setDetectRenames(true)
                for (c in rw) {
                    if (skipping) {
                        if (c == beforeCommit) skipping = false
                        continue
                    }
                    if (sinceEpoch != null && c.commitTime.toLong() < sinceEpoch) break
                    if (out.size == max) { more = true; break }
                    val parentTree = if (c.parentCount > 0) rw.parseCommit(c.getParent(0)).tree else null
                    val all = df.scan(parentTree, c.tree).map(::pathChange)
                    out += ChangeEntry(
                        commit = c.name,
                        authorName = c.authorIdent.name,
                        authorEmail = c.authorIdent.emailAddress,
                        epochSeconds = c.authorIdent.whenAsInstant.epochSecond,
                        message = c.fullMessage.trim(),
                        agent = agentTrailer(c.fullMessage),
                        paths = all.take(CHANGES_MAX_PATHS),
                        pathCount = all.size,
                        pathsTruncated = all.size > CHANGES_MAX_PATHS,
                    )
                }
            }
        }
        return ChangesPage(head.name, out, if (more) out.lastOrNull()?.commit else null)
    }

    private fun resolveCommitOrNull(rev: String): ObjectId? = try {
        repo.resolve(rev)?.let { id -> RevWalk(repo).use { it.parseCommit(id).id } }
    } catch (_: Exception) {
        null
    }

    private fun pathChange(e: org.eclipse.jgit.diff.DiffEntry): PathChange = when (e.changeType) {
        org.eclipse.jgit.diff.DiffEntry.ChangeType.ADD -> PathChange(e.newPath, ChangeKind.ADD)
        org.eclipse.jgit.diff.DiffEntry.ChangeType.MODIFY -> PathChange(e.newPath, ChangeKind.MODIFY)
        org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE -> PathChange(e.oldPath, ChangeKind.DELETE)
        org.eclipse.jgit.diff.DiffEntry.ChangeType.COPY -> PathChange(e.newPath, ChangeKind.ADD, e.oldPath)
        else -> {
            val toTrash = e.newPath.startsWith(TRASH_PREFIX)
            val fromTrash = e.oldPath.startsWith(TRASH_PREFIX)
            val kind = when {
                toTrash && !fromTrash -> ChangeKind.DELETE
                fromTrash && !toTrash -> ChangeKind.RESTORE
                else -> ChangeKind.RENAME
            }
            PathChange(e.newPath, kind, e.oldPath)
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

        /** Changed paths listed per commit in [changes]; the rest is counted, not listed. */
        private const val CHANGES_MAX_PATHS = 200
        private const val TRASH_PREFIX = ".trash/"

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
