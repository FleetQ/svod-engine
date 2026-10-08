package dev.svod.engine.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the sync conflicts surfaced by the merge authority — the data behind
 * `GET /api/v1/conflicts`. A conflict is never auto-resolved: each entry carries base/ours/
 * theirs so a 3-way merge UI (or an agent) can resolve it, then call [resolve].
 *
 * Open conflicts live in memory (every cycle re-plans them). A **resolution** is durable: it is
 * keyed by the path and the incoming blob it answers, saved to [resolutionsFile], and consumed by
 * the merge commit that folds that incoming version in. So a restart, or a held merge dropped
 * because a write raced it, does not turn an answered conflict back into an open one.
 */
class ConflictStore(
    private val resolutionsFile: Path? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class SyncConflict(
        val path: String,
        val reasons: List<String>,
        val base: String?,
        val ours: String?,
        val theirs: String?,
        val ts: Long,
        /** Blob id of the incoming version (null = deleted there); what a resolution is keyed on. */
        val theirsBlob: String? = null,
        /** The incoming version tripped the secret scanner and was never written. */
        val quarantined: Boolean = false,
    )

    /** How a conflict was answered. */
    enum class Choice {
        /** Keep the local file as it stands (the user's merged write, or "keep mine"). */
        ours,
        /** Take the incoming version as is — for a quarantined file, a deliberate override of the secret scan. */
        incoming,
    }

    @Serializable
    data class Resolution(val theirsBlob: String?, val choice: Choice)

    private val byPath = ConcurrentHashMap<String, SyncConflict>()
    private val resolutions = ConcurrentHashMap<String, Resolution>(load())
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun record(
        path: String, base: String?, ours: String?, theirs: String?, reasons: List<String>,
        theirsBlob: String? = null, quarantined: Boolean = false,
    ) {
        val prev = byPath[path]
        // Re-surfaced every cycle: keep the first-seen time while the conflict itself is unchanged.
        val ts = if (prev != null && prev.theirsBlob == theirsBlob && prev.ours == ours) prev.ts else clock()
        byPath[path] = SyncConflict(path, reasons, base, ours, theirs, ts, theirsBlob, quarantined)
    }

    /** Drop open conflicts that are no longer in [paths] (the remote moved on, or a peer converged). */
    fun retainOnly(paths: Set<String>) {
        byPath.keys.retainAll(paths)
    }

    fun get(path: String): SyncConflict? = byPath[path]

    /**
     * Clear the conflict at [path] and remember the answer for the incoming version it showed, so
     * the next cycle merges that version in with [choice] instead of raising it again.
     */
    fun resolve(path: String, choice: Choice = Choice.ours) {
        val c = byPath.remove(path) ?: return
        resolutions[path] = Resolution(c.theirsBlob, choice)
        save()
    }

    /** The answer recorded for [path], if it was given for incoming blob [theirsBlob]. */
    fun resolution(path: String, theirsBlob: String?): Choice? =
        resolutions[path]?.takeIf { it.theirsBlob == theirsBlob }?.choice

    /** Forget answers once a merge commit folded their incoming versions in. */
    fun consumed(paths: Collection<String>) {
        if (paths.isEmpty()) return
        paths.forEach { resolutions.remove(it) }
        save()
    }

    fun all(): List<SyncConflict> = byPath.values.sortedBy { it.path }

    fun isEmpty(): Boolean = byPath.isEmpty()

    private fun load(): Map<String, Resolution> {
        val f = resolutionsFile ?: return emptyMap()
        if (!Files.isRegularFile(f)) return emptyMap()
        return runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(SERIALIZER, Files.readString(f)) }.getOrDefault(emptyMap())
    }

    @Synchronized
    private fun save() {
        val f = resolutionsFile ?: return
        Files.createDirectories(f.parent)
        val tmp = f.resolveSibling(f.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(SERIALIZER, HashMap(resolutions)))
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        val SERIALIZER = MapSerializer(String.serializer(), Resolution.serializer())
    }
}
