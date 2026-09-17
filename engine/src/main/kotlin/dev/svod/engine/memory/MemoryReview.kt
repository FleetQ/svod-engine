package dev.svod.engine.memory

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.MarkdownChunker
import dev.svod.engine.index.ParsedDoc
import dev.svod.engine.index.SearchFilters

/** [fm] serialized as a `---` fenced YAML block, key order kept. */
fun frontmatterFences(fm: Map<String, Any?>): String {
    val opts = org.yaml.snakeyaml.DumperOptions().apply {
        defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
        isAllowUnicode = true
    }
    val yaml = org.yaml.snakeyaml.Yaml(opts).dump(fm).trimEnd('\n')
    return "---\n$yaml\n---\n"
}

/**
 * The review queue for agent-written memory. `remember` stores fact/policy as `provisional`, which the
 * default recall filter hides, so without a person confirming them most memories never reach an agent.
 * Approval is deliberately not an MCP tool: the gate exists so an agent cannot confirm its own memory.
 */
object MemoryReview {

    data class ReviewItem(
        val path: String,
        val title: String,
        val excerpt: String,
        val type: String?,
        val status: String?,
        val subject: String?,
        val confidence: Double?,
        val source: String?,
        val created: String?,
        val contradicts: String?,
        val supersedes: String?,
        val needsReview: Boolean,
        val revision: String,
    )

    data class RulebookItem(val path: String, val title: String, val type: String, val subject: String?, val summary: String)

    enum class Action(val wire: String, val status: String) {
        APPROVE("approve", "active"),
        DECLINE("decline", "revoked"),
        REOPEN("reopen", "provisional");

        companion object {
            fun of(wire: String): Action? = entries.firstOrNull { it.wire == wire }
        }
    }

    sealed interface ReviewOutcome {
        /** The engine's write result; only [WriteOutcome.Success] means the new [status] is stored. */
        data class Written(val outcome: WriteOutcome, val status: String) : ReviewOutcome
        data class NotMemory(val path: String) : ReviewOutcome
        data class Missing(val path: String) : ReviewOutcome
        data class Superseded(val path: String, val supersededBy: String) : ReviewOutcome
    }

    const val DEFAULT_LIMIT = 200
    const val MAX_LIMIT = 500
    const val RULEBOOK_DEFAULT_LIMIT = 40
    const val RULEBOOK_MAX_LIMIT = 200
    val RULEBOOK_DEFAULT_TYPES = listOf("policy", "preference")

    private const val EXCERPT_CHARS = 280
    private const val SUMMARY_CHARS = 160
    private const val TITLE_CHARS = 80

    /** Upper bound on notes read per call; `total` saturates here. */
    private const val SCAN_CAP = 10_000

    private val HEADING = Regex("^#{1,6}\\s+(.*)$")
    private val WHITESPACE = Regex("\\s+")

    /** Up to [limit] queue items in review order (design D4), plus the full count. */
    suspend fun list(engine: SvodEngine, index: IndexService, limit: Int): Pair<List<ReviewItem>, Int> {
        val now = System.currentTimeMillis() / 1000
        val items = ArrayList<ReviewItem>()
        for (path in index.enumerateReview(SCAN_CAP)) {
            val f = engine.read(path) ?: continue
            val doc = MarkdownChunker.parse(f.text)
            // The index can lag a file that was just written; the file decides.
            if (!awaitsReview(path, doc, now)) continue
            items += itemOf(path, f.revision, doc)
        }
        items.sortWith(
            compareByDescending<ReviewItem> { it.needsReview || it.contradicts != null }
                .thenByDescending { it.created?.let(java.time.Instant::parse) }
                .thenBy { it.path },
        )
        return items.take(limit.coerceIn(0, MAX_LIMIT)) to items.size
    }

    suspend fun awaitingCount(engine: SvodEngine, index: IndexService): Int = list(engine, index, 0).second

    /** Apply [action] to the memory at [path] as a frontmatter rewrite committed by [author]. */
    suspend fun apply(engine: SvodEngine, path: String, action: Action, expectedRevision: String?, author: Author): ReviewOutcome {
        val current = engine.read(path) ?: return ReviewOutcome.Missing(path)
        val doc = MarkdownChunker.parse(current.text)
        if (path.startsWith(SESSIONS_PREFIX) || ("status" !in doc.frontmatter && "type" !in doc.frontmatter)) {
            return ReviewOutcome.NotMemory(path)
        }
        doc.supersededBy?.let { return ReviewOutcome.Superseded(path, it) }

        val fm = LinkedHashMap<String, Any?>(doc.frontmatter)
        fm["status"] = action.status
        if (action != Action.REOPEN) {
            fm.remove("needs-review")
            fm.remove("needsReview")
        }
        fm["reviewed_at"] = java.time.Instant.now().toString()
        fm["reviewed_by"] = author.name
        // Body appended untouched: a review changes the memory's state, never what it says.
        val text = frontmatterFences(fm) + doc.body
        return ReviewOutcome.Written(engine.write(path, text, expectedRevision ?: current.revision, author), action.status)
    }

    /**
     * The confirmed rule book: default-visible notes of [types] as one line each, sorted by type then
     * title. An index for a session start, not the notes themselves — the model reads one on demand.
     */
    suspend fun rulebook(engine: SvodEngine, index: IndexService, types: List<String>, limit: Int): List<RulebookItem> {
        val now = System.currentTimeMillis() / 1000
        val items = ArrayList<RulebookItem>()
        for (type in types.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()) {
            for (path in index.enumerate(SearchFilters(type = type), SCAN_CAP)) {
                if (path.startsWith("messy/")) continue
                val f = engine.read(path) ?: continue
                val doc = MarkdownChunker.parse(f.text)
                if (doc.private || doc.type != type || !recallVisible(doc, now)) continue
                val masked = MarkdownChunker.stripPrivateSpans(doc.body)
                val summary = masked.lineSequence().map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !HEADING.matches(it) }
                    .orEmpty().take(SUMMARY_CHARS)
                items += RulebookItem(path, titleOf(path, doc, masked), type, stringOf(doc, "subject"), summary)
            }
        }
        items.sortWith(compareBy<RulebookItem> { it.type }.thenBy { it.title.lowercase() }.thenBy { it.path })
        return items.take(limit.coerceIn(0, RULEBOOK_MAX_LIMIT))
    }

    private fun awaitsReview(path: String, doc: ParsedDoc, now: Long): Boolean =
        !path.startsWith(SESSIONS_PREFIX) && !doc.private &&
            (doc.status == "provisional" || doc.needsReview) &&
            doc.status != "revoked" && doc.supersededBy == null && !expired(doc, now)

    private fun recallVisible(doc: ParsedDoc, now: Long): Boolean =
        doc.status != "provisional" && doc.status != "revoked" && doc.supersededBy == null && !expired(doc, now)

    private fun expired(doc: ParsedDoc, now: Long): Boolean = doc.expiresAt != null && doc.expiresAt <= now

    private fun itemOf(path: String, revision: String, doc: ParsedDoc): ReviewItem {
        val masked = MarkdownChunker.stripPrivateSpans(doc.body)
        return ReviewItem(
            path = path,
            title = titleOf(path, doc, masked),
            excerpt = masked.replace(WHITESPACE, " ").trim().take(EXCERPT_CHARS),
            type = doc.type,
            status = doc.status,
            subject = stringOf(doc, "subject"),
            confidence = when (val c = doc.frontmatter["confidence"]) {
                is Number -> c.toDouble()
                is String -> c.trim().toDoubleOrNull()
                else -> null
            },
            source = stringOf(doc, "source"),
            created = doc.created?.let { java.time.Instant.ofEpochSecond(it).toString() },
            contradicts = stringOf(doc, "contradicts"),
            supersedes = stringOf(doc, "supersedes"),
            needsReview = doc.needsReview,
            revision = revision,
        )
    }

    /** Frontmatter title, else the first heading, else the first line of the masked body, else the file name. */
    private fun titleOf(path: String, doc: ParsedDoc, maskedBody: String): String {
        doc.title?.takeIf { it.isNotEmpty() }?.let { return it }
        val lines = maskedBody.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        lines.firstNotNullOfOrNull { HEADING.find(it)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty) }?.let { return it }
        lines.firstOrNull()?.let { return it.take(TITLE_CHARS) }
        return path.substringAfterLast('/').removeSuffix(".md")
    }

    private fun stringOf(doc: ParsedDoc, key: String): String? =
        doc.frontmatter[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
