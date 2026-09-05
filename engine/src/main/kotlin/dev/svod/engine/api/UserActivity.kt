package dev.svod.engine.api

import dev.svod.engine.security.SecretFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * When each personal key was last used to authenticate — so an admin can see a key that has
 * gone quiet (a leaver's) in Members instead of trusting that revoke was remembered.
 *
 * In memory it is exact; on disk it is written at most once per [minIntervalMs] per user, so a
 * busy editor does not turn every request into a file write. Persistence is best-effort: an
 * unwritable file is a warning, never a failed authentication.
 */
class UserActivity(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 60_000,
) {
    private val lastUsed = ConcurrentHashMap<String, Long>()
    private val lastPersisted = ConcurrentHashMap<String, Long>()
    private val lock = Any()

    init { load() }

    fun touch(userId: String) {
        val now = clock()
        lastUsed[userId] = now
        val persisted = lastPersisted[userId]
        if (persisted == null || now - persisted >= minIntervalMs) {
            lastPersisted[userId] = now
            persist()
        }
    }

    fun lastUsed(userId: String): Long? = lastUsed[userId]

    /** ISO-8601 UTC, the shape the contract exposes as `lastUsedAt`. */
    fun lastUsedIso(userId: String): String? = lastUsed(userId)?.let { Instant.ofEpochMilli(it).toString() }

    fun load() {
        if (!Files.exists(file)) return
        runCatching {
            val obj = Json.parseToJsonElement(Files.readString(file)).jsonObject
            for ((k, v) in obj) v.jsonPrimitive.longOrNull?.let { lastUsed[k] = it; lastPersisted[k] = it }
        }.onFailure { log.warn("user activity: cannot read {}: {}", file, it.message) }
    }

    private fun persist() {
        val snapshot = HashMap(lastUsed)
        synchronized(lock) {
            runCatching {
                val body = buildJsonObject { for ((k, v) in snapshot) put(k, JsonPrimitive(v)) }.toString()
                // First write creates the file 0600 (SecretFiles); later writes go through the
                // codebase's one atomic writer (fsync + rename). The rename brings the temp file's
                // default mode with it, so 0600 is re-applied — this file names people.
                if (!Files.exists(file)) SecretFiles.write(file, body)
                else {
                    dev.svod.engine.core.AtomicFile.write(file, body.toByteArray())
                    SecretFiles.restrict(file)
                }
            }.onFailure { log.warn("user activity: cannot write {}: {}", file, it.message) }
        }
    }

    private companion object {
        val log = org.slf4j.LoggerFactory.getLogger(UserActivity::class.java)
    }
}
