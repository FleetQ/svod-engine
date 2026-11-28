package dev.svod.engine.core

/** Identity attributed to a mutation; becomes the git author/committer. */
data class Author(val name: String, val email: String, val agent: String? = null) {
    companion object {
        val SYSTEM = Author("svod", "svod@localhost")
        val RECOVERY = Author("svod-recovery", "recovery@svod.localhost")
        val EXTERNAL = Author("external", "external@svod.localhost")
    }
}

/**
 * A revision is the git blob object id (hex) of a file's exact bytes.
 *
 * It is content-addressed, so it is stable and identical whether computed from
 * the working-tree bytes or from content a client is about to write. That is the
 * basis for optimistic concurrency: compare the client's expected revision to the
 * current file's blob id; a mismatch is a conflict.
 */
typealias Revision = String

/** Result of any mutating engine operation. Failures are values, not exceptions. */
sealed interface WriteOutcome {

    /** The mutation was applied and committed. */
    data class Success(
        val path: String,
        val revision: Revision,
        val commit: String,
    ) : WriteOutcome

    /**
     * The caller's expected revision did not match the current state; nothing was
     * written. [current] is the live revision (null if the file does not exist) and
     * [currentContent] is the live content so the caller/UI can perform a 3-way merge.
     */
    data class Conflict(
        val path: String,
        val expected: Revision?,
        val current: Revision?,
        val currentContent: String?,
    ) : WriteOutcome

    /** The target did not exist. */
    data class NotFound(val path: String) : WriteOutcome

    /** The write was refused because [findings] (e.g. leaked secrets) were detected; nothing committed. */
    data class Blocked(val path: String, val findings: List<String>) : WriteOutcome
}

/**
 * Result of [SvodEngine.writeGuarded] — a multi-file write whose precondition was checked on the
 * write-actor, immediately before applying.
 */
sealed interface GuardedWrite {

    /** Every guard held; all files were written and committed together. */
    data class Applied(val commit: String, val revisions: Map<String, Revision>) : GuardedWrite

    /**
     * A guarded path no longer matched the revision the caller planned against — nothing was
     * written. The caller re-plans against live state rather than clobbering it.
     */
    data class Stale(val path: String, val expected: Revision?, val current: Revision?) : GuardedWrite

    /** Refused because [findings] (e.g. leaked secrets) were detected in one of the files. */
    data class Blocked(val path: String, val findings: List<String>) : GuardedWrite
}

/** A file's content together with its current revision. */
data class FileContent(
    val path: String,
    val revision: Revision,
    val text: String,
)

/** Result of a move that also rewrote backlinks; [rewrittenBacklinks] are the touched notes. */
data class TransactionalMove(
    val outcome: WriteOutcome,
    val rewrittenBacklinks: List<String> = emptyList(),
)

/** One entry in a file's (or the vault's) history. */
data class CommitInfo(
    val commit: String,
    val authorName: String,
    val authorEmail: String,
    val epochSeconds: Long,
    val message: String,
    /** `Svod-Agent:` trailer of the commit message (ADR-0020), null when absent. */
    val agent: String? = null,
)

/** How a commit touched a path in the change feed. A soft delete into `.trash/` is DELETE, a move out of it RESTORE. */
enum class ChangeKind { ADD, MODIFY, DELETE, RENAME, RESTORE }

/** One changed path of a commit; [oldPath] is set for RENAME, DELETE (to `.trash/`) and RESTORE. */
data class PathChange(val path: String, val kind: ChangeKind, val oldPath: String? = null)

/** One commit of the vault-wide change feed, with its changed paths (capped; see [pathsTruncated]). */
data class ChangeEntry(
    val commit: String,
    val authorName: String,
    val authorEmail: String,
    val epochSeconds: Long,
    val message: String,
    /** `Svod-Agent:` trailer of the commit message (ADR-0020), null when absent. */
    val agent: String?,
    val paths: List<PathChange>,
    /** Number of changed paths before the per-commit cap. */
    val pathCount: Int,
    val pathsTruncated: Boolean,
)

/** A page of the change feed, newest first. [nextCursor] is the `before` value for the next (older) page. */
data class ChangesPage(val head: String?, val changes: List<ChangeEntry>, val nextCursor: String?)

/** Who a note's creation / last change is attributed to; derived from git only. */
data class Attribution(val name: String, val email: String, val agent: String? = null)

class VaultLockedException(root: Any) : IllegalStateException("vault already locked by another instance: $root")
