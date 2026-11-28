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

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true }

        /** The vault's agent audit trail file. */
        fun fileFor(vaultRoot: Path): Path = vaultRoot.resolve(".svod").resolve("audit").resolve("audit.log")

        /**
         * Read-only query over [file], newest first. Never creates, writes or truncates the file (so it does
         * not construct an [AuditLog], whose init creates directories). Each line's [AuditRecord.seq] is its
         * 1-based line number, a stable cursor: [before] drops entries with seq >= before. A line that does
         * not decode (garbage, a half-written last line) is skipped and counted in [AuditPage.skipped].
         * [outcomes] null or empty means any outcome; [sinceMs] keeps entries with ts >= sinceMs.
         */
        fun query(
            file: Path,
            agent: String? = null,
            outcomes: Set<String>? = null,
            sinceMs: Long? = null,
            before: Long? = null,
            limit: Int = 100,
        ): AuditPage {
            if (!Files.isRegularFile(file)) return AuditPage(emptyList(), 0, 0, null)
            var skipped = 0
            val matches = ArrayList<AuditRecord>()
            // Split raw bytes on '\n' and decode each line on its own with a strict UTF-8 decoder, so a
            // torn multi-byte character spoils only its own line (skipped), never the whole read.
            val bytes = Files.readAllBytes(file)
            val lines = ArrayList<ByteArray>()
            var start = 0
            for (k in bytes.indices) if (bytes[k] == '\n'.code.toByte()) { lines += bytes.copyOfRange(start, k); start = k + 1 }
            if (start < bytes.size) lines += bytes.copyOfRange(start, bytes.size)
            lines.forEachIndexed { i, raw ->
                val line = runCatching {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(raw)).toString()
                }.getOrNull()
                if (line == null) { skipped++; return@forEachIndexed }
                if (line.isBlank()) return@forEachIndexed
                val e = runCatching { lenient.decodeFromString(AuditEntry.serializer(), line) }.getOrNull()
                if (e == null) { skipped++; return@forEachIndexed }
                if (agent != null && e.agentId != agent) return@forEachIndexed
                if (!outcomes.isNullOrEmpty() && e.outcome !in outcomes) return@forEachIndexed
                if (sinceMs != null && e.ts < sinceMs) return@forEachIndexed
                matches += AuditRecord(i + 1L, e.ts, e.agentId, e.tool, e.outcome, e.path, e.target, e.revision, e.detail)
            }
            matches.reverse()   // lines are in append order, so reversed is seq descending
            val eligible = if (before == null) matches else matches.filter { it.seq < before }
            val page = eligible.take(limit.coerceAtLeast(0))
            val next = if (eligible.size > page.size && page.isNotEmpty()) page.last().seq else null
            return AuditPage(page, matches.size, skipped, next)
        }
    }
}

/** An [AuditEntry] with its 1-based line number in the trail. */
@Serializable
data class AuditRecord(
    val seq: Long,
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
 * One page of [AuditLog.query]. [total] counts all matches of the filters (before paging); [skipped]
 * counts undecodable lines; [nextBefore] is the cursor for the next (older) page, null when none remain.
 */
data class AuditPage(val entries: List<AuditRecord>, val total: Int, val skipped: Int, val nextBefore: Long?)
