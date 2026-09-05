package dev.svod.engine.api

import dev.svod.engine.security.SecretFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Who did what on the App API — one JSON line per request by a PERSON (a keyed principal). The
 * loopback UI on a single-user engine is not recorded: there is nobody to distinguish it from.
 *
 * Mirrors [dev.svod.engine.mcp.AuditLog] (agents). Never holds a request body, a query string
 * (only the `vault` parameter) or an Authorization header value. The file is created 0600: it
 * names people and what they read.
 */
class ApiAuditLog(private val file: Path, private val clock: () -> Long = System::currentTimeMillis) {

    @Serializable
    data class Entry(
        val ts: Long,
        val userId: String,
        val method: String,
        val path: String,
        val vault: String? = null,
        val status: Int,
        val ip: String? = null,
    )

    private val lock = Any()
    private val json = Json { encodeDefaults = false }

    fun record(userId: String, method: String, path: String, vault: String?, status: Int, ip: String?) {
        val line = json.encodeToString(Entry.serializer(), Entry(clock(), userId, method, path, vault, status, ip)) + "\n"
        synchronized(lock) {
            runCatching { SecretFiles.append(file, line) }
                .onFailure { log.warn("api audit: cannot append to {}: {}", file, it.message) }
        }
    }

    fun entries(): List<Entry> = synchronized(lock) {
        if (!Files.exists(file)) emptyList()
        else Files.readAllLines(file).filter { it.isNotBlank() }.map { json.decodeFromString(Entry.serializer(), it) }
    }

    private companion object {
        val log = org.slf4j.LoggerFactory.getLogger(ApiAuditLog::class.java)
    }
}
