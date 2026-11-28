package dev.svod.engine.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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
    /** The previous chained entry's [hash], or [AuditLog.GENESIS] for the first one. Null on pre-chain lines. */
    val prevHash: String? = null,
    /** Lowercase hex SHA-256 of this entry with [prevHash] set and [hash] left out ([AuditLog.hashOf]). */
    val hash: String? = null,
)

/**
 * Append-only audit trail of agent actions, one JSON object per line under
 * `.svod/audit/audit.log`. Appends are serialized per file and flushed so the trail is durable and
 * ordered. Each new entry carries [AuditEntry.prevHash] (the previous entry's hash, or [GENESIS])
 * and [AuditEntry.hash] (SHA-256 of the entry without `hash`), so the lines form a hash chain:
 * [verify] finds the first edited, removed or reordered line, or a line inserted after the chain
 * starts. Lines written before the chain existed have no hash and count as pre-chain. The chain has
 * no anchor outside the file, so a full rewrite, lines cut off the end and hash-less lines added
 * before the first chained line are not detected.
 */
class AuditLog(private val file: Path, private val clock: () -> Long = System::currentTimeMillis) {

    // One lock per file: SvodNode and AppApiServer each build their own AuditLog for the same file.
    private val lock: Any = locks.computeIfAbsent(file.toAbsolutePath().normalize()) { Any() }

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
        synchronized(lock) {
            // A missing file, a pre-chain line or an undecodable (torn) last line starts the chain at GENESIS.
            val prev = lastNonBlankLine(file)
                ?.let { runCatching { lenient.decodeFromString(AuditEntry.serializer(), it) }.getOrNull() }
                ?.hash ?: GENESIS
            val unhashed = AuditEntry(clock(), agentId, tool, outcome, path, target, revision, detail, prevHash = prev)
            val entry = unhashed.copy(hash = hashOf(unhashed))
            val line = json.encodeToString(AuditEntry.serializer(), entry) + "\n"
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
        /** The writer's format; encodeDefaults=false keeps older lines and the field order unchanged. */
        private val json = Json { encodeDefaults = false }
        private val locks = ConcurrentHashMap<Path, Any>()
        private const val TAIL_CHUNK = 8192

        /** [AuditEntry.prevHash] of the first chained entry. */
        val GENESIS: String = "0".repeat(64)

        /** Lowercase hex SHA-256 of the UTF-8 JSON of [e] without its hash (fields in declaration order). */
        fun hashOf(e: AuditEntry): String {
            val bytes = json.encodeToString(AuditEntry.serializer(), e.copy(hash = null)).toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }

        /** The vault's agent audit trail file. */
        fun fileFor(vaultRoot: Path): Path = vaultRoot.resolve(".svod").resolve("audit").resolve("audit.log")

        private fun isBlankByte(b: Byte): Boolean = b == '\n'.code.toByte() || b == '\r'.code.toByte() ||
            b == ' '.code.toByte() || b == '\t'.code.toByte()

        /** The last non-blank line of [file], read backwards from the end in chunks; null when none or not UTF-8. */
        private fun lastNonBlankLine(file: Path): String? {
            if (!Files.isRegularFile(file)) return null
            FileChannel.open(file, StandardOpenOption.READ).use { ch ->
                var pos = ch.size()
                var tail = ByteArray(0)
                while (true) {
                    var end = tail.size
                    while (end > 0 && isBlankByte(tail[end - 1])) end--
                    if (end > 0) {
                        var nl = end - 1
                        while (nl >= 0 && tail[nl] != '\n'.code.toByte()) nl--
                        if (nl >= 0 || pos == 0L) return decodeStrict(tail.copyOfRange(nl + 1, end))
                    } else if (pos == 0L) {
                        return null
                    }
                    val n = minOf(TAIL_CHUNK.toLong(), pos).toInt()
                    pos -= n
                    val buf = ByteBuffer.allocate(n)
                    while (buf.hasRemaining()) {
                        if (ch.read(buf, pos + buf.position()) < 0) break
                    }
                    tail = buf.array().copyOf(buf.position()) + tail
                }
            }
        }

        private fun decodeStrict(raw: ByteArray): String? = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
        }.getOrNull()

        /** Raw lines of [file] split on '\n', each decoded on its own (null when not valid UTF-8). */
        private fun readLines(file: Path): List<String?> {
            // Split raw bytes on '\n' and decode each line on its own with a strict UTF-8 decoder, so a
            // torn multi-byte character spoils only its own line, never the whole read.
            return splitLines(Files.readAllBytes(file))
        }

        private fun splitLines(bytes: ByteArray): List<String?> {
            val lines = ArrayList<String?>()
            var start = 0
            for (k in bytes.indices) if (bytes[k] == '\n'.code.toByte()) { lines += decodeStrict(bytes.copyOfRange(start, k)); start = k + 1 }
            if (start < bytes.size) lines += decodeStrict(bytes.copyOfRange(start, bytes.size))
            return lines
        }

        /**
         * Read-only check of [file]'s hash chain; never creates or writes anything. Leading lines without
         * a hash are pre-chain (so hash-less lines added before the first chained line are not detected).
         * From the first chained line on, every non-blank line must decode, be byte-for-byte its canonical
         * encoding (no extra keys, reordered keys or changed whitespace), carry a hash equal to [hashOf] and a
         * prevHash equal to the previous line's hash ([GENESIS] for the first). Stops at the first failure
         * with its 1-based line number. Blank lines are skipped. A failing last line with no newline is
         * reported as incomplete: it may still be being written, or was torn by a crash.
         */
        fun verify(file: Path): AuditChainReport {
            if (!Files.isRegularFile(file)) return AuditChainReport(AuditChainReport.Status.ABSENT, 0, 0, null, null)
            val bytes = Files.readAllBytes(file)
            val lines = splitLines(bytes)
            val lastUnterminated = bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()
            var chained = 0
            var preChain = 0
            var prev: String? = null
            lines.forEachIndexed { i, line ->
                fun broken(reason: String): AuditChainReport {
                    val r = if (lastUnterminated && i == lines.lastIndex) {
                        "incomplete last line (still being written or torn by a crash; run again): $reason"
                    } else reason
                    return AuditChainReport(AuditChainReport.Status.BROKEN, chained, preChain, i + 1L, r)
                }
                if (line != null && line.isBlank()) return@forEachIndexed
                val e = line?.let { runCatching { lenient.decodeFromString(AuditEntry.serializer(), it) }.getOrNull() }
                    ?: return broken("undecodable line")
                if (e.hash == null) {
                    if (prev != null) return broken("unchained line after the chain started (inserted)")
                    preChain++
                    return@forEachIndexed
                }
                if (line != json.encodeToString(AuditEntry.serializer(), e)) {
                    return broken("line not in canonical form (line edited)")
                }
                if (e.hash != hashOf(e)) return broken("hash mismatch (line edited)")
                if (e.prevHash != (prev ?: GENESIS)) {
                    return broken("prevHash mismatch (a line before it was removed, reordered or inserted)")
                }
                prev = e.hash
                chained++
            }
            return AuditChainReport(AuditChainReport.Status.OK, chained, preChain, null, null)
        }

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
            // A torn multi-byte character spoils only its own line (skipped), never the whole read.
            readLines(file).forEachIndexed { i, line ->
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

/**
 * Result of [AuditLog.verify]: [chained] and [preChain] count the lines checked before any break;
 * [brokenLine] (1-based) and [reason] are set only when [status] is BROKEN.
 */
data class AuditChainReport(val status: Status, val chained: Int, val preChain: Int, val brokenLine: Long?, val reason: String?) {
    enum class Status { ABSENT, OK, BROKEN }
}
