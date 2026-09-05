package dev.svod.engine.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** One recorded action. Mutations are always audited; reads may be audited too. */
@Serializable
data class AuditEntry(
    val ts: Long,
    val agentId: String,
    val tool: String,
    val outcome: String,
    val path: String? = null,
    val target: String? = null,
    val revision: String? = null,
    val detail: String? = null,
)

/**
 * Append-only audit trail of agent actions, one JSON object per line under
 * `.svod/audit/audit.log`. Appends are serialized and flushed so the trail is durable and
 * ordered. This is the tamper-evident record behind "auditable agent memory".
 */
class AuditLog(private val file: Path, private val clock: () -> Long = System::currentTimeMillis) {

    private val lock = Any()
    private val json = Json { encodeDefaults = false }

    init {
        Files.createDirectories(file.parent)
    }

    fun record(
        agentId: String,
        tool: String,
        outcome: String,
        path: String? = null,
        target: String? = null,
        revision: String? = null,
        detail: String? = null,
    ) {
        val entry = AuditEntry(clock(), agentId, tool, outcome, path, target, revision, detail)
        val line = json.encodeToString(AuditEntry.serializer(), entry) + "\n"
        synchronized(lock) {
            // Agent ids and note paths: owner-only, like the App API audit (creates 0600, restricts an older 0644 file).
            dev.svod.engine.security.SecretFiles.append(file, line)
        }
    }

    /** Read the trail back (for inspection / tests). */
    fun entries(): List<AuditEntry> {
        if (!Files.isRegularFile(file)) return emptyList()
        return Files.readAllLines(file).filter { it.isNotBlank() }.map { json.decodeFromString(AuditEntry.serializer(), it) }
    }
}
