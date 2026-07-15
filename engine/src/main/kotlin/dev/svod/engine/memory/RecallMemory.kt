package dev.svod.engine.memory

import dev.svod.engine.index.MarkdownChunker
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The "recall" memory subsystem: session capture + a suggestions inbox (proposals), stored under
 * the vault. Captured session transcripts live under [SESSIONS_PREFIX] — a `messy/` subtree that
 * is UNCONDITIONALLY excluded from recall (search + context_pack) at the index-query boundary, so
 * raw transcripts (which may hold secrets) never leak the way `<private>` and `messy/` don't.
 *
 * The engine only STORES/SERVES/MARKS: the LLM distillation that compresses transcripts into curated
 * notes and derives proposals is done EXTERNALLY by a scheduled agent that calls the App API. Byte
 * accounting (captured vs distilled) comes from note sizes, never from any generative call.
 */
const val SESSIONS_PREFIX = "messy/sessions/"

/** A capture proposal appended by the external distiller — a suggestion, never auto-actioned. */
@Serializable
data class Proposal(
    val id: String,
    val kind: String,      // "skill" | "tool"
    val title: String,
    val scope: String,     // "project" | "global"
    val confidence: Double,
    val rationale: String,
    val sourceSessions: List<String>,
    val createdAt: Long,
    val status: String,    // "open" | "accepted" | "rejected"
    val note: String? = null,
)

/** A captured session note's frontmatter, independent of the (raw) transcript body. */
data class SessionMeta(
    val path: String,
    val project: String?,
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long,
    val bytes: Long,
    val distilled: Boolean,
)

/** Pure helpers for the session-note format (path, frontmatter build/parse, distilled flip). */
object SessionNotes {
    /** Path-safe project slug for the filename (letters/digits kept, others → '-'); "none" when blank. */
    fun slug(project: String?): String {
        val s = (project ?: "").lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .trim('-').take(40)
        return s.ifEmpty { "none" }
    }

    fun pathFor(endedAt: Long, project: String?, sessionId: String): String =
        "$SESSIONS_PREFIX$endedAt-${slug(project)}-${sessionId.take(8).ifEmpty { "session" }}.md"

    /** Frontmatter + RAW transcript. `bytes` is the transcript's UTF-8 size (captured-byte accounting). */
    fun buildNote(project: String?, sessionId: String, startedAt: Long, endedAt: Long, transcript: String): String {
        val bytes = transcript.toByteArray(Charsets.UTF_8).size
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("type: session\n")
        if (project != null) sb.append("project: ").append(yamlScalar(project)).append('\n')
        sb.append("sessionId: ").append(yamlScalar(sessionId)).append('\n')
        sb.append("startedAt: ").append(startedAt).append('\n')
        sb.append("endedAt: ").append(endedAt).append('\n')
        sb.append("distilled: false\n")
        sb.append("bytes: ").append(bytes).append('\n')
        sb.append("---\n")
        sb.append(transcript)
        return sb.toString()
    }

    /** Parse a session note's frontmatter into [SessionMeta]; null if it is not a `type: session` note. */
    fun parseMeta(path: String, text: String): SessionMeta? {
        val fm = MarkdownChunker.parse(text).frontmatter
        if (fm["type"]?.toString()?.trim()?.lowercase() != "session") return null
        val sessionId = fm["sessionId"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return SessionMeta(
            path = path,
            project = fm["project"]?.toString(),
            sessionId = sessionId,
            startedAt = asLong(fm["startedAt"]) ?: 0,
            endedAt = asLong(fm["endedAt"]) ?: 0,
            bytes = asLong(fm["bytes"]) ?: 0,
            distilled = asBool(fm["distilled"]),
        )
    }

    /** Flip the frontmatter `distilled:` line to true (first occurrence — the frontmatter one). */
    fun withDistilled(text: String): String = DISTILLED_LINE.replaceFirst(text, "distilled: true")

    private val DISTILLED_LINE = Regex("^distilled:.*$", RegexOption.MULTILINE)

    private fun yamlScalar(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "-_./" }) s
        else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun asLong(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    private fun asBool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is String -> v.trim().lowercase() in setOf("true", "yes", "1", "on")
        else -> false
    }
}

/**
 * Persistence for the proposals inbox + distill accounting, under the gitignored `<root>/.svod/`.
 * Proposals are `recall-proposals.json`; the distill stats (curated note refs + last-distill time)
 * are `recall-distill.json`. Both are small append/update stores guarded by a process lock.
 */
class MemoryStore(root: Path) {
    private val dir: Path = root.resolve(".svod")
    private val proposalsFile: Path = dir.resolve("recall-proposals.json")
    private val statsFile: Path = dir.resolve("recall-distill.json")
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val lock = Any()

    @Serializable
    private data class DistillStats(val noteRefs: List<String> = emptyList(), val lastDistillAt: Long? = null)

    fun proposals(): List<Proposal> = synchronized(lock) { readProposals() }

    /**
     * Append a proposal, deduped by (kind, title, scope) via a derived id. Returns the newly stored
     * proposal, or the pre-existing one when a matching triple is already present (no duplicate row).
     */
    fun appendProposal(
        kind: String, title: String, scope: String, confidence: Double,
        rationale: String, sourceSessions: List<String>, createdAt: Long,
    ): Proposal = synchronized(lock) {
        val id = idFor(kind, title, scope)
        val current = readProposals()
        current.firstOrNull { it.id == id }?.let { return it }
        val p = Proposal(id, kind, title, scope, confidence, rationale, sourceSessions, createdAt, "open")
        writeProposals(current + p)
        p
    }

    /** Status transition only (accept/reject + optional note). Null when [id] is unknown. */
    fun updateProposal(id: String, status: String, note: String?): Proposal? = synchronized(lock) {
        val current = readProposals().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = current[idx].copy(status = status, note = note ?: current[idx].note)
        current[idx] = updated
        writeProposals(current)
        updated
    }

    /** Record the curated note refs a distill pass produced (union, deduped) + stamp last-distill. */
    fun recordDistill(noteRefs: List<String>, at: Long): Unit = synchronized(lock) {
        val s = readStats()
        writeStats(DistillStats((s.noteRefs + noteRefs).distinct(), at))
    }

    fun distilledNoteRefs(): List<String> = synchronized(lock) { readStats().noteRefs }
    fun lastDistillAt(): Long? = synchronized(lock) { readStats().lastDistillAt }

    private fun readProposals(): List<Proposal> =
        if (!Files.isRegularFile(proposalsFile)) emptyList()
        else json.decodeFromString(PROPOSALS, Files.readString(proposalsFile))

    private fun writeProposals(list: List<Proposal>) {
        Files.createDirectories(dir)
        Files.writeString(proposalsFile, json.encodeToString(PROPOSALS, list))
    }

    private fun readStats(): DistillStats =
        if (!Files.isRegularFile(statsFile)) DistillStats()
        else json.decodeFromString(DistillStats.serializer(), Files.readString(statsFile))

    private fun writeStats(stats: DistillStats) {
        Files.createDirectories(dir)
        Files.writeString(statsFile, json.encodeToString(DistillStats.serializer(), stats))
    }

    private companion object {
        val PROPOSALS = ListSerializer(Proposal.serializer())

        /** Deterministic id keyed on the dedup triple — same (kind,title,scope) ⇒ same id ⇒ dedup. */
        fun idFor(kind: String, title: String, scope: String): String =
            MessageDigest.getInstance("SHA-256").digest("$kind|$title|$scope".toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }
    }
}
