package dev.svod.engine.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the sync conflicts surfaced by the merge authority — the data behind
 * `GET /api/v1/conflicts`. A conflict is never auto-resolved: each entry carries base/ours/
 * theirs so a 3-way merge UI (or an agent) can resolve it, then call [resolve].
 */
class ConflictStore(private val clock: () -> Long = System::currentTimeMillis) {

    data class SyncConflict(
        val path: String,
        val reasons: List<String>,
        val base: String?,
        val ours: String?,
        val theirs: String?,
        val ts: Long,
    )

    private val byPath = ConcurrentHashMap<String, SyncConflict>()

    fun record(path: String, base: String?, ours: String?, theirs: String?, reasons: List<String>) {
        byPath[path] = SyncConflict(path, reasons, base, ours, theirs, clock())
    }

    fun resolve(path: String) {
        byPath.remove(path)
    }

    fun all(): List<SyncConflict> = byPath.values.sortedBy { it.path }

    fun isEmpty(): Boolean = byPath.isEmpty()
}
