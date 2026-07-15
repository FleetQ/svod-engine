package dev.svod.engine.core

import dev.svod.engine.graph.LinkRewriter
import dev.svod.engine.security.SecretScanner
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * The integrity core: a single-writer, git-backed vault that never loses files.
 *
 * Every mutation runs on the [WriteActor] thread, is written atomically, and is
 * committed to git with the caller's identity. Optimistic concurrency rejects stale
 * writes with a [WriteOutcome.Conflict] carrying the live content. Deletes are soft
 * (moved to `.trash/`). On open, crash recovery reconciles the tree with git before
 * any work is served.
 *
 * The engine is OS-agnostic; all platform specifics live above it (server, lifecycle, UI).
 */
class SvodEngine private constructor(
    val root: Path,
    private val lock: VaultLock,
    private val git: GitRepo,
    private val actor: WriteActor,
    /** Test-only crash injector; null-armed (no-op) in production. */
    val crash: CrashInjection,
    private val secretScanner: SecretScanner,
) : AutoCloseable {

    /** Write-path metrics (latency); queue depth is read from the actor. */
    val metrics = dev.svod.engine.obs.Metrics()
    fun queueDepth(): Int = actor.queueDepth()
    fun peakQueueDepth(): Int = actor.peakQueueDepth()

    private suspend fun <T> timed(op: suspend () -> T): T {
        val start = System.nanoTime()
        try {
            return op()
        } finally {
            metrics.recordWrite(System.nanoTime() - start)
        }
    }

    /**
     * Optional observer notified (off the actor thread) with the new HEAD after each
     * successful mutating commit. Used to drive derived state — e.g. the index — without
     * coupling it into the write path. The callback must be non-blocking.
     */
    @Volatile
    private var commitListener: ((commit: String) -> Unit)? = null

    fun onCommit(listener: (commit: String) -> Unit) { commitListener = listener }

    private fun notifyCommit(outcome: WriteOutcome) {
        if (outcome is WriteOutcome.Success) commitListener?.invoke(outcome.commit)
    }

    // ---- public API (all serialized through the write-actor) ----

    suspend fun write(path: String, content: String, expectedRevision: Revision?, author: Author): WriteOutcome =
        timed { actor.submit { doWrite(VaultPath.of(path), content, expectedRevision, author) } }.also(::notifyCommit)

    /**
     * Binary-safe write: stores arbitrary [bytes] verbatim (attachments — images, PDF, …) through
     * the same single writer with the same atomic + optimistic-concurrency guarantees. Unlike
     * [write] it does not secret-scan (binaries aren't prose). Binaries are committed but the
     * index leaves them un-embedded (it only indexes `.md`).
     */
    suspend fun writeBytes(path: String, bytes: ByteArray, expectedRevision: Revision?, author: Author): WriteOutcome =
        timed { actor.submit { doWriteBytes(VaultPath.of(path), bytes, expectedRevision, author) } }.also(::notifyCommit)

    /**
     * Write many files in ONE commit (bulk import). Each entry is atomically written, then a single
     * git commit covers the whole batch — collapsing the per-write commit cost that dominates a
     * large import. Idempotent + non-clobbering like the import path: a file already present with
     * identical content is `unchanged`; one present with different content is `skipped` (never
     * overwritten); a text entry tripping the secret scanner is `skipped`. Still one write-actor
     * submission, so single-writer integrity holds and nothing else can interleave the batch.
     */
    suspend fun writeBatch(
        entries: List<BatchEntry>,
        author: Author,
        message: String = "import: ${entries.size} files",
        overwrite: Boolean = false,
        /**
         * Per-path expected on-disk revision (blob id), captured by the caller when it classified the
         * batch. In [overwrite] mode an entry whose live blob no longer matches its expected value is a
         * `conflict` (skipped, never clobbered) — this closes the classify→write race for source sync,
         * where a vault edit can land between the read and the batch write. Empty ⇒ unconditional
         * overwrite (the original behavior; import passes nothing).
         */
        expected: Map<String, String?> = emptyMap(),
    ): BatchResult =
        timed { actor.submit { doBatch(entries, author, message, overwrite, expected) } }
            .also { r -> if (r.written.isNotEmpty()) r.commit?.let { commitListener?.invoke(it) } }

    /** Git blob id (content hash) of [bytes] — the same value used as a file's revision. Pure, no I/O. */
    fun blobId(bytes: ByteArray): String = git.blobId(bytes)

    suspend fun read(path: String): FileContent? = actor.submit {
        val vp = VaultPath.of(path)
        val target = vp.resolveAgainst(root)
        if (!Files.isRegularFile(target)) return@submit null
        val bytes = Files.readAllBytes(target)
        FileContent(vp.value, git.blobId(bytes), String(bytes, UTF_8))
    }

    /** Raw bytes of [path], or null if absent. Byte-accurate (unlike [read], which decodes UTF-8). */
    suspend fun readBytes(path: String): ByteArray? = actor.submit {
        val target = VaultPath.of(path).resolveAgainst(root)
        if (Files.isRegularFile(target)) Files.readAllBytes(target) else null
    }

    suspend fun delete(path: String, expectedRevision: Revision?, author: Author): WriteOutcome =
        timed { actor.submit { doDelete(VaultPath.of(path), expectedRevision, author) } }.also(::notifyCommit)

    suspend fun move(from: String, to: String, expectedRevision: Revision?, author: Author): WriteOutcome =
        timed { actor.submit { doMove(VaultPath.of(from), VaultPath.of(to), expectedRevision, author) } }.also(::notifyCommit)

    /**
     * Move a note and transactionally rewrite every `[[wikilink]]` that referenced it, so the
     * move and all reference updates land in a single commit. This is link-integrity: a
     * rename never silently breaks backlinks.
     */
    suspend fun moveWithLinks(from: String, to: String, expectedRevision: Revision?, author: Author): TransactionalMove =
        timed { actor.submit { doMoveWithLinks(VaultPath.of(from), VaultPath.of(to), expectedRevision, author) } }
            .also { notifyCommit(it.outcome) }

    /** Restore a soft-deleted file from `.trash/` back to [to] (defaults to its original path). */
    suspend fun restore(trashRelPath: String, to: String? = null, author: Author): WriteOutcome =
        actor.submit { doRestore(trashRelPath, to, author) }.also(::notifyCommit)

    /** Most-recent-first commits touching [path], capped at [max] (default 100). Renames are not followed. */
    suspend fun history(path: String, max: Int = 100): List<CommitInfo> =
        actor.submit { git.log(VaultPath.of(path).value, max) }

    /** Content of [path] at a specific [revision] (commit id / ref), or null if absent there. */
    suspend fun getRevision(path: String, revision: String): FileContent? = actor.submit {
        val vp = VaultPath.of(path)
        val bytes = git.readAtRevision(vp.value, revision) ?: return@submit null
        FileContent(vp.value, git.blobId(bytes), String(bytes, UTF_8))
    }

    /** Unified diff of [path] between two revisions (commit ids / refs). */
    suspend fun diff(path: String, fromRevision: String, toRevision: String): String =
        actor.submit { git.diff(VaultPath.of(path).value, fromRevision, toRevision) }

    /**
     * Promote a draft from `messy/` into the curated vault — a transactional move that
     * enforces the namespace policy (source under `messy/`, target outside it).
     */
    suspend fun promote(from: String, to: String, expectedRevision: Revision?, author: Author): WriteOutcome {
        require(from.replace('\\', '/').trimStart('/').startsWith("messy/")) { "promote source must be under messy/: $from" }
        require(!to.replace('\\', '/').trimStart('/').startsWith("messy/")) { "promote target must not be under messy/: $to" }
        return move(from, to, expectedRevision, author)
    }

    /** Vault-relative paths of all user files (excludes dot-directories and engine internals). */
    suspend fun list(): List<String> = actor.submit {
        val out = ArrayList<String>()
        walkUserFiles { out.add(root.relativize(it).toString().replace('\\', '/')) }
        out.sorted()
    }

    /**
     * Every `.md` note's content, path → text, read in ONE actor pass. For derived snapshots (the
     * link graph, tag counts) that need all note bodies but not per-file revisions: this is a single
     * tree walk with direct reads, vs. N round-trips + N git blob hashes from [list] + per-path [read]
     * (which made /file/links O(notes) and seconds-slow on large vaults).
     */
    suspend fun readAllNotes(): Map<String, String> = actor.submit { readAllNotesOnActor() }

    suspend fun head(): String? = actor.submit { git.headId() }

    fun branch(): String = git.branch()

    // ---- sync write primitives (actor-serialized; keep the single-writer guarantee) ----

    /** Secret-scan [content] (defense-in-depth for incoming sync files); empty ⇒ clean. */
    fun scanSecrets(content: String): List<String> = secretScanner.scan(content).map { "${it.rule} (line ${it.line})" }

    /**
     * Fast-forward the vault to [commit] (a descendant fetched from a peer). When [expectedHead] is
     * given, the move is applied on the actor ONLY if HEAD still equals it — so a local write that
     * landed mid-sync (HEAD moved) aborts the fast-forward (returns false) instead of discarding it;
     * the caller re-syncs. Returns true when the vault was moved to [commit].
     */
    suspend fun fastForwardTo(commit: String, expectedHead: String? = null): Boolean {
        val moved = actor.submit {
            if (expectedHead != null && git.headId() != expectedHead) false
            else { git.resetHardTo(commit); true }
        }
        if (moved) commitListener?.invoke(commit)
        return moved
    }

    /**
     * Apply a merge: write [writes] (merged file contents) and remove [deletes], then create a
     * MERGE commit with parents [HEAD, theirs]. Conflicted files are simply not in [writes]
     * (ours is kept), so they are never silently overwritten.
     *
     * When [expectedHead] is given, the merge commit is created on the actor ONLY if HEAD still
     * equals it — a concurrent local write (HEAD moved between planning the merge and applying it)
     * aborts the apply (returns null) so the caller re-plans against the new HEAD. This keeps the
     * merge atomic with respect to local writes without a cross-handle lock.
     */
    suspend fun applyMerge(
        writes: Map<String, String>,
        deletes: List<String>,
        theirs: String,
        message: String,
        author: Author,
        expectedHead: String? = null,
    ): String? {
        val commit = actor.submit {
            if (expectedHead != null && git.headId() != expectedHead) return@submit null
            for ((path, content) in writes) {
                AtomicFile.write(VaultPath.of(path).resolveAgainst(root), content.toByteArray(UTF_8), crash)
            }
            for (path in deletes) Files.deleteIfExists(VaultPath.of(path).resolveAgainst(root))
            git.commitMerge(message, author, theirs)
        }
        if (commit != null) commitListener?.invoke(commit)
        return commit
    }

    /**
     * Commit any working-tree changes made OUTSIDE the engine (e.g. a user editing a file in
     * another app, or a git pull) as an [author] commit, so they enter history + the index.
     * Runs on the write-actor, so it can never interleave with an engine write — a change
     * made through the engine is already committed and yields no-op here. Returns the new
     * commit id, or null if the working tree was already clean.
     */
    suspend fun ingestExternalChanges(author: Author = Author.EXTERNAL): String? = actor.submit {
        cleanOrphanTmp()
        val before = git.headId()
        val after = git.commitAll("external: ingest working-tree changes", author)
        if (after != null && after != before) after else null
    }.also { commit -> if (commit != null) commitListener?.invoke(commit) }

    /**
     * Path-scoped ingest for the [dev.svod.engine.watch.FileWatcher], which knows exactly which paths
     * its FS events touched. Committing only those paths is O(changes), not O(working tree) — and a
     * change the engine itself just made is already committed, so it is a cheap no-op here (no
     * full-tree `add .`/`status` walk, which is ~tens of seconds on a large vault and was blocking the
     * write-actor on every engine write). The full-scan [ingestExternalChanges] above stays for
     * post-sync ingest (a merge can touch arbitrary paths the caller doesn't enumerate).
     */
    suspend fun ingestExternalChanges(paths: Collection<String>, author: Author = Author.EXTERNAL): String? = actor.submit {
        cleanOrphanTmp()
        val before = git.headId()
        val after = git.commitPaths(paths.toList(), "external: ingest working-tree changes", author)
        if (after != null && after != before) after else null
    }.also { commit -> if (commit != null) commitListener?.invoke(commit) }

    override fun close() {
        try { actor.close() } finally {
            try { git.close() } finally { lock.close() }
        }
    }

    // ---- mutation handlers (run ON the actor thread; blocking I/O is fine here) ----

    private fun doWrite(vp: VaultPath, content: String, expected: Revision?, author: Author): WriteOutcome {
        val secrets = secretScanner.scan(content)
        if (secrets.isNotEmpty()) {
            return WriteOutcome.Blocked(vp.value, secrets.map { "${it.rule} (line ${it.line})" })
        }
        val target = vp.resolveAgainst(root)
        val exists = Files.isRegularFile(target)
        val current = if (exists) git.blobId(Files.readAllBytes(target)) else null
        if (current != expected) {
            val live = if (exists) Files.readString(target, UTF_8) else null
            return WriteOutcome.Conflict(vp.value, expected, current, live)
        }
        val bytes = content.toByteArray(UTF_8)
        AtomicFile.write(target, bytes, crash)
        val commit = git.commitPaths(listOf(vp.value), "write: ${vp.value}", author) ?: git.headId()!!
        return WriteOutcome.Success(vp.value, git.blobId(bytes), commit)
    }

    private fun doWriteBytes(vp: VaultPath, bytes: ByteArray, expected: Revision?, author: Author): WriteOutcome {
        val target = vp.resolveAgainst(root)
        val exists = Files.isRegularFile(target)
        val current = if (exists) git.blobId(Files.readAllBytes(target)) else null
        if (current != expected) {
            // Binary live content isn't returned (a lossy UTF-8 decode would be meaningless); the
            // current blob id is what a caller needs to retry.
            return WriteOutcome.Conflict(vp.value, expected, current, null)
        }
        AtomicFile.write(target, bytes, crash)
        val commit = git.commitPaths(listOf(vp.value), "write: ${vp.value}", author) ?: git.headId()!!
        return WriteOutcome.Success(vp.value, git.blobId(bytes), commit)
    }

    private fun doBatch(entries: List<BatchEntry>, author: Author, message: String, overwrite: Boolean, expected: Map<String, String?>): BatchResult {
        val written = ArrayList<String>()
        val unchanged = ArrayList<String>()
        val skipped = ArrayList<String>()
        val conflicts = ArrayList<String>()
        for (e in entries) {
            val vp = VaultPath.of(e.path)
            val incoming: ByteArray = when (e) {
                is BatchEntry.Text -> {
                    if (secretScanner.scan(e.content).isNotEmpty()) { skipped.add(vp.value); continue }
                    e.content.toByteArray(UTF_8)
                }
                is BatchEntry.Bytes -> e.bytes
            }
            val target = vp.resolveAgainst(root)
            val curBytes = if (Files.isRegularFile(target)) Files.readAllBytes(target) else null
            // Present & identical ⇒ unchanged. Different ⇒ skipped, UNLESS overwrite (source sync,
            // where the caller has already decided the incoming content wins) ⇒ written.
            if (curBytes != null && curBytes.contentEquals(incoming)) { unchanged.add(vp.value); continue }
            if (curBytes != null && !overwrite) { skipped.add(vp.value); continue }
            // Overwrite mode with an expected revision: re-validate the live blob against what the
            // caller classified. A mismatch means the vault changed between classify and now — a
            // conflict, never a silent clobber. (Runs on the actor, so the check + write are atomic.)
            if (overwrite && expected.containsKey(vp.value) && curBytes?.let { git.blobId(it) } != expected[vp.value]) {
                conflicts.add(vp.value); continue
            }
            AtomicFile.write(target, incoming, crash)
            written.add(vp.value)
        }
        // One commit for the whole batch (the win); no commit when nothing was written.
        val commit = if (written.isNotEmpty()) (git.commitPaths(written, message, author) ?: git.headId()) else git.headId()
        return BatchResult(written.sorted(), unchanged.sorted(), skipped.sorted(), commit, conflicts.sorted())
    }

    private fun doDelete(vp: VaultPath, expected: Revision?, author: Author): WriteOutcome {
        val target = vp.resolveAgainst(root)
        if (!Files.isRegularFile(target)) return WriteOutcome.NotFound(vp.value)
        val bytes = Files.readAllBytes(target)
        val current = git.blobId(bytes)
        if (current != expected) {
            return WriteOutcome.Conflict(vp.value, expected, current, String(bytes, UTF_8))
        }
        val trashTarget = uniqueTrashTarget(vp)
        Files.createDirectories(trashTarget.parent)
        Files.move(target, trashTarget, StandardCopyOption.ATOMIC_MOVE)
        cleanupEmptyParents(target.parent)
        val trashRel = root.relativize(trashTarget).toString().replace('\\', '/')
        val commit = git.commitPaths(listOf(vp.value, trashRel), "delete: ${vp.value} -> $trashRel", author) ?: git.headId()!!
        return WriteOutcome.Success(trashRel, current, commit)
    }

    private fun doMove(fromP: VaultPath, toP: VaultPath, expected: Revision?, author: Author): WriteOutcome {
        val from = fromP.resolveAgainst(root)
        val to = toP.resolveAgainst(root)
        if (!Files.isRegularFile(from)) return WriteOutcome.NotFound(fromP.value)
        val bytes = Files.readAllBytes(from)
        val current = git.blobId(bytes)
        if (current != expected) {
            return WriteOutcome.Conflict(fromP.value, expected, current, String(bytes, UTF_8))
        }
        if (Files.exists(to)) {
            val destBytes = Files.readAllBytes(to)
            return WriteOutcome.Conflict(toP.value, null, git.blobId(destBytes), String(destBytes, UTF_8))
        }
        Files.createDirectories(to.parent)
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        cleanupEmptyParents(from.parent)
        val commit = git.commitPaths(listOf(fromP.value, toP.value), "move: ${fromP.value} -> ${toP.value}", author) ?: git.headId()!!
        return WriteOutcome.Success(toP.value, current, commit)
    }

    private fun doMoveWithLinks(fromP: VaultPath, toP: VaultPath, expected: Revision?, author: Author): TransactionalMove {
        val from = fromP.resolveAgainst(root)
        val to = toP.resolveAgainst(root)
        if (!Files.isRegularFile(from)) return TransactionalMove(WriteOutcome.NotFound(fromP.value))
        val bytes = Files.readAllBytes(from)
        val current = git.blobId(bytes)
        if (current != expected) {
            return TransactionalMove(WriteOutcome.Conflict(fromP.value, expected, current, String(bytes, UTF_8)))
        }
        if (Files.exists(to)) {
            val destBytes = Files.readAllBytes(to)
            return TransactionalMove(WriteOutcome.Conflict(toP.value, null, git.blobId(destBytes), String(destBytes, UTF_8)))
        }

        // Snapshot every note BEFORE moving so backlink detection sees pre-move content/paths.
        val notes = readAllNotesOnActor()
        val rewriter = LinkRewriter(notes.keys)

        Files.createDirectories(to.parent)
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        cleanupEmptyParents(from.parent)

        val rewritten = ArrayList<String>()
        for ((path, content) in notes) {
            if (path == fromP.value) continue
            val (updated, changed) = rewriter.rewrite(content, fromP.value, toP.value)
            if (changed) {
                AtomicFile.write(root.resolve(path), updated.toByteArray(UTF_8), crash)
                rewritten.add(path)
            }
        }

        val message = if (rewritten.isEmpty()) {
            "move: ${fromP.value} -> ${toP.value}"
        } else {
            "move: ${fromP.value} -> ${toP.value} (+${rewritten.size} backlinks)"
        }
        val commit = git.commitPaths(listOf(fromP.value, toP.value) + rewritten, message, author) ?: git.headId()!!
        return TransactionalMove(WriteOutcome.Success(toP.value, current, commit), rewritten.sorted())
    }

    private fun readAllNotesOnActor(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        walkUserFiles { f ->
            if (f.fileName.toString().endsWith(".md")) {
                out[root.relativize(f).toString().replace('\\', '/')] = Files.readString(f, UTF_8)
            }
        }
        return out
    }

    private fun doRestore(trashRelPath: String, to: String?, author: Author): WriteOutcome {
        val trashRoot = root.resolve(".trash").normalize()
        val src = root.resolve(trashRelPath).normalize()
        require(src.startsWith(trashRoot) && src != trashRoot) { "not a .trash path: $trashRelPath" }
        if (!Files.isRegularFile(src)) return WriteOutcome.NotFound(trashRelPath)

        val destRel = to ?: trashRoot.relativize(src).toString().replace('\\', '/')
        val vp = VaultPath.of(destRel)
        val dest = vp.resolveAgainst(root)
        if (Files.exists(dest)) {
            val destBytes = Files.readAllBytes(dest)
            return WriteOutcome.Conflict(vp.value, null, git.blobId(destBytes), String(destBytes, UTF_8))
        }
        Files.createDirectories(dest.parent)
        Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE)
        val bytes = Files.readAllBytes(dest)
        val commit = git.commitPaths(listOf(trashRelPath, vp.value), "restore: $trashRelPath -> ${vp.value}", author) ?: git.headId()!!
        return WriteOutcome.Success(vp.value, git.blobId(bytes), commit)
    }

    // ---- crash recovery (runs once on open, before the actor serves callers) ----

    /**
     * Reconcile the working tree with git history after a possibly-unclean shutdown:
     *  1. delete orphan `.svod-tmp` files left by interrupted atomic writes
     *  2. complete any uncommitted change by committing it as a recovery commit
     *
     * We COMPLETE rather than roll back: a file that reached the working tree was
     * fsync'd and atomically renamed, so it is real and durable — discarding it would
     * violate "never lose files". Half-written content is impossible by construction.
     */
    private fun recover() {
        cleanOrphanTmp()
        git.commitAll("recovery: complete interrupted write", Author.RECOVERY)
    }

    private fun cleanOrphanTmp() {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root) {
                    val name = dir.fileName.toString()
                    if (name == ".git" || name == ".svod") return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (AtomicFile.isTmp(file)) Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
        })
    }

    // ---- helpers ----

    private fun walkUserFiles(action: (Path) -> Unit) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && dir.fileName.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!file.fileName.toString().startsWith(".")) action(file)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun uniqueTrashTarget(vp: VaultPath): Path {
        val base = root.resolve(".trash").resolve(vp.value)
        if (!Files.exists(base)) return base
        // Collision (same path deleted before): disambiguate without overwriting history.
        var n = 1
        while (true) {
            val candidate = base.resolveSibling("${base.fileName}.$n")
            if (!Files.exists(candidate)) return candidate
            n++
        }
    }

    private fun cleanupEmptyParents(start: Path) {
        var dir = start
        val base = root.normalize()
        while (dir.normalize() != base && Files.isDirectory(dir)) {
            val empty = Files.newDirectoryStream(dir).use { !it.iterator().hasNext() }
            if (!empty) break
            val parent = dir.parent
            try { Files.delete(dir) } catch (_: IOException) { break }
            dir = parent
        }
    }

    companion object {
        private val GITIGNORE = """
            # svod engine internals
            .svod/
            *${AtomicFile.TMP_SUFFIX}
        """.trimIndent() + "\n"

        /**
         * Open (or initialize) a vault at [root]. Acquires the single-instance lock,
         * ensures git + scaffold exist, then runs crash recovery before returning a
         * ready engine. [scope] owns the write-actor coroutine.
         */
        fun open(
            root: Path,
            scope: CoroutineScope,
            secretScanner: SecretScanner = SecretScanner.OFF,
            committer: Author? = null,
        ): SvodEngine {
            Files.createDirectories(root)
            val lock = VaultLock.acquire(root)
            try {
                // The vault lock we just acquired guarantees no other live engine owns this vault, so a
                // leftover .git/index.lock can only be stale debris from an unclean kill (SIGKILL / power
                // loss / KeepAlive after a hard crash). Left in place it makes the first git write throw
                // jgit LockFailedException, which crash-loops the engine under launchd KeepAlive. Acquiring
                // the vault lock is the interlock that makes removing it safe; do it before any git write.
                clearStaleGitLock(root)
                val git = GitRepo.openOrInit(root, committer)
                ensureScaffold(root, git)
                val engine = SvodEngine(root, lock, git, WriteActor(scope), CrashInjection(), secretScanner)
                engine.recover()
                return engine
            } catch (t: Throwable) {
                lock.close()
                throw t
            }
        }

        private val log = org.slf4j.LoggerFactory.getLogger(SvodEngine::class.java)

        /**
         * Remove a stale `<root>/.git/index.lock` left by a previously-killed engine. Safe ONLY because
         * the caller already holds the exclusive [VaultLock] (no other live engine can own the index).
         * No-op when the repo or the lock doesn't exist (e.g. a brand-new vault).
         */
        private fun clearStaleGitLock(root: Path) {
            val indexLock = root.resolve(".git").resolve("index.lock")
            if (Files.exists(indexLock) && runCatching { Files.deleteIfExists(indexLock) }.getOrDefault(false)) {
                log.warn("removed stale git index.lock at {} (left by an unclean shutdown)", indexLock)
            }
        }

        private fun ensureScaffold(root: Path, git: GitRepo) {
            val gitignore = root.resolve(".gitignore")
            if (!Files.exists(gitignore)) AtomicFile.write(gitignore, GITIGNORE.toByteArray(UTF_8))

            val trash = root.resolve(".trash")
            Files.createDirectories(trash)
            val keep = trash.resolve(".gitkeep")
            if (!Files.exists(keep)) AtomicFile.write(keep, ByteArray(0))

            if (git.headId() == null) git.commitAll("init: svod vault", Author.SYSTEM)
        }
    }
}

/** One file in a [SvodEngine.writeBatch]: text (secret-scanned + indexed) or raw bytes (attachment). */
sealed class BatchEntry {
    abstract val path: String
    data class Text(override val path: String, val content: String) : BatchEntry()
    data class Bytes(override val path: String, val bytes: ByteArray) : BatchEntry() {
        override fun equals(other: Any?) = this === other || (other is Bytes && path == other.path && bytes.contentEquals(other.bytes))
        override fun hashCode() = 31 * path.hashCode() + bytes.contentHashCode()
    }
}

/** Outcome of [SvodEngine.writeBatch]: per-path classification + the single batch commit (or HEAD). */
data class BatchResult(
    val written: List<String>,
    val unchanged: List<String>,
    val skipped: List<String>,
    val commit: String?,
    /** Paths not written because the live blob no longer matched the caller's expected revision (overwrite mode). */
    val conflicts: List<String> = emptyList(),
)
