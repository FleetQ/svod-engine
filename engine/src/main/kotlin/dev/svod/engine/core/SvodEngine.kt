package dev.svod.engine.core

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
) : AutoCloseable {

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
        actor.submit { doWrite(VaultPath.of(path), content, expectedRevision, author) }.also(::notifyCommit)

    suspend fun read(path: String): FileContent? = actor.submit {
        val vp = VaultPath.of(path)
        val target = vp.resolveAgainst(root)
        if (!Files.isRegularFile(target)) return@submit null
        val bytes = Files.readAllBytes(target)
        FileContent(vp.value, git.blobId(bytes), String(bytes, UTF_8))
    }

    suspend fun delete(path: String, expectedRevision: Revision?, author: Author): WriteOutcome =
        actor.submit { doDelete(VaultPath.of(path), expectedRevision, author) }.also(::notifyCommit)

    suspend fun move(from: String, to: String, expectedRevision: Revision?, author: Author): WriteOutcome =
        actor.submit { doMove(VaultPath.of(from), VaultPath.of(to), expectedRevision, author) }.also(::notifyCommit)

    /** Restore a soft-deleted file from `.trash/` back to [to] (defaults to its original path). */
    suspend fun restore(trashRelPath: String, to: String? = null, author: Author): WriteOutcome =
        actor.submit { doRestore(trashRelPath, to, author) }.also(::notifyCommit)

    suspend fun history(path: String, max: Int = 50): List<CommitInfo> =
        actor.submit { git.log(VaultPath.of(path).value, max) }

    /** Vault-relative paths of all user files (excludes dot-directories and engine internals). */
    suspend fun list(): List<String> = actor.submit {
        val out = ArrayList<String>()
        walkUserFiles { out.add(root.relativize(it).toString().replace('\\', '/')) }
        out.sorted()
    }

    suspend fun head(): String? = actor.submit { git.headId() }

    override fun close() {
        try { actor.close() } finally {
            try { git.close() } finally { lock.close() }
        }
    }

    // ---- mutation handlers (run ON the actor thread; blocking I/O is fine here) ----

    private fun doWrite(vp: VaultPath, content: String, expected: Revision?, author: Author): WriteOutcome {
        val target = vp.resolveAgainst(root)
        val exists = Files.isRegularFile(target)
        val current = if (exists) git.blobId(Files.readAllBytes(target)) else null
        if (current != expected) {
            val live = if (exists) Files.readString(target, UTF_8) else null
            return WriteOutcome.Conflict(vp.value, expected, current, live)
        }
        val bytes = content.toByteArray(UTF_8)
        AtomicFile.write(target, bytes, crash)
        val commit = git.commitAll("write: ${vp.value}", author) ?: git.headId()!!
        return WriteOutcome.Success(vp.value, git.blobId(bytes), commit)
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
        val commit = git.commitAll("delete: ${vp.value} -> $trashRel", author) ?: git.headId()!!
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
        val commit = git.commitAll("move: ${fromP.value} -> ${toP.value}", author) ?: git.headId()!!
        return WriteOutcome.Success(toP.value, current, commit)
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
        val commit = git.commitAll("restore: $trashRelPath -> ${vp.value}", author) ?: git.headId()!!
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
        fun open(root: Path, scope: CoroutineScope): SvodEngine {
            Files.createDirectories(root)
            val lock = VaultLock.acquire(root)
            try {
                val git = GitRepo.openOrInit(root)
                ensureScaffold(root, git)
                val engine = SvodEngine(root, lock, git, WriteActor(scope), CrashInjection())
                engine.recover()
                return engine
            } catch (t: Throwable) {
                lock.close()
                throw t
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
