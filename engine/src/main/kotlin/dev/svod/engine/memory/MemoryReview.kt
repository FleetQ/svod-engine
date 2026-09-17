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

private val FRONTMATTER_BLOCK = Regex("^\\uFEFF?---\\r?\\n(.*?)\\r?\\n---\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
private val TOP_LEVEL_KEY = Regex("^([A-Za-z_][A-Za-z0-9_-]*)\\s*:(.*)$")
private val PLAIN_SCALAR = Regex("^[a-z]+$")

/**
 * [raw] with top-level frontmatter keys in [set] replaced (or appended) and keys in [remove] deleted,
 * line by line, so every other byte stays as written. Re-dumping the whole map instead rewrote lines the
 * caller never touched: SnakeYAML loads an unquoted `created: 2026-09-01T10:00:00Z` as a Date and dumps
 * it in another form, and comments are lost. Returns null when the patch is not unambiguous (a touched
 * key repeated, or holding a block, multi-line or flow value) or the result does not parse back to
 * exactly the expected frontmatter and body; the caller then falls back to [frontmatterFences].
 */
fun patchFrontmatter(raw: String, set: Map<String, String>, remove: Set<String>): String? {
    val m = FRONTMATTER_BLOCK.find(raw) ?: return null
    val block = m.groups[1]!!
    val newline = if (raw.substring(0, block.range.first).endsWith("\r\n")) "\r\n" else "\n"
    val lines = block.value.split("\n").map { it.removeSuffix("\r") }.toMutableList()
    val touched = set.keys + remove

    val index = HashMap<String, Int>()
    for ((i, line) in lines.withIndex()) {
        val key = TOP_LEVEL_KEY.find(line)?.groupValues?.get(1) ?: continue
        if (key !in touched) continue
        if (index.put(key, i) != null) return null
        val value = TOP_LEVEL_KEY.find(line)!!.groupValues[2].trim()
        val continued = lines.getOrNull(i + 1)?.let { it.startsWith(" ") || it.startsWith("\t") || it.startsWith("-") } == true
        if (value.isEmpty() || value[0] in "|>[{&*!" || continued) return null
    }

    fun scalar(v: String) = if (PLAIN_SCALAR.matches(v)) v else "'" + v.replace("'", "''") + "'"
    for ((key, value) in set) index[key]?.let { lines[it] = "$key: ${scalar(value)}" }
    for (i in index.filterKeys { it in remove }.values.sortedDescending()) lines.removeAt(i)
    for ((key, value) in set) if (key !in index) lines += "$key: ${scalar(value)}"

    val patched = raw.substring(0, block.range.first) + lines.joinToString(newline) + raw.substring(block.range.last + 1)
    val before = MarkdownChunker.parse(raw)
    val after = MarkdownChunker.parse(patched)
    val expected = LinkedHashMap(before.frontmatter).apply { keys.removeAll(remove); putAll(set) }
    return patched.takeIf { after.frontmatter == expected && after.body == before.body }
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
        val items = queue(NoteCache(engine), index).map { (path, revision, doc) -> itemOf(path, revision, doc) }.toMutableList()
        items.sortWith(
            compareByDescending<ReviewItem> { it.needsReview || it.contradicts != null }
                .thenByDescending { it.created?.let(java.time.Instant::parse) }
                .thenBy { it.path },
        )
        return items.take(limit.coerceIn(0, MAX_LIMIT)) to items.size
    }

    /** The queue size, i.e. `list(...).second`, without building excerpts or sorting. */
    suspend fun awaitingCount(engine: SvodEngine, index: IndexService): Int = queue(NoteCache(engine), index).size

    /** One read and parse per path per call, shared by the rule book and its queue count. */
    private class NoteCache(private val engine: SvodEngine) {
        private val notes = HashMap<String, Pair<String, ParsedDoc>?>()
        suspend fun get(path: String): Pair<String, ParsedDoc>? =
            if (path in notes) notes[path] else engine.read(path)?.let { it.revision to MarkdownChunker.parse(it.text) }.also { notes[path] = it }
    }

    private suspend fun queue(cache: NoteCache, index: IndexService): List<Triple<String, String, ParsedDoc>> {
        val now = System.currentTimeMillis() / 1000
        return index.enumerateReview(SCAN_CAP).mapNotNull { path ->
            val (revision, doc) = cache.get(path) ?: return@mapNotNull null
            // The index can lag a file that was just written; the file decides.
            Triple(path, revision, doc).takeIf { awaitsReview(path, doc, now) }
        }
    }

    /** Apply [action] to the memory at [path] as a frontmatter rewrite committed by [author]. */
    suspend fun apply(engine: SvodEngine, path: String, action: Action, expectedRevision: String?, author: Author): ReviewOutcome {
        val current = engine.read(path) ?: return ReviewOutcome.Missing(path)
        val doc = MarkdownChunker.parse(current.text)
        if (path.startsWith(SESSIONS_PREFIX) || ("status" !in doc.frontmatter && "type" !in doc.frontmatter)) {
            return ReviewOutcome.NotMemory(path)
        }
        doc.supersededBy?.let { return ReviewOutcome.Superseded(path, it) }

        val set = linkedMapOf("status" to action.status, "reviewed_at" to java.time.Instant.now().toString(), "reviewed_by" to author.name)
        val remove = if (action != Action.REOPEN) setOf("needs-review", "needsReview") else emptySet()
        // The body is never touched: a review changes the memory's state, never what it says.
        val text = patchFrontmatter(current.text, set, remove)
            ?: (frontmatterFences(LinkedHashMap<String, Any?>(doc.frontmatter).apply { keys.removeAll(remove); putAll(set) }) + doc.body)
        return ReviewOutcome.Written(engine.write(path, text, expectedRevision ?: current.revision, author), action.status)
    }

    /**
     * The confirmed rule book: default-visible notes of [types] as one line each, sorted by type then
     * title. An index for a session start, not the notes themselves — the model reads one on demand.
     */
    suspend fun rulebook(engine: SvodEngine, index: IndexService, types: List<String>, limit: Int): Pair<List<RulebookItem>, Int> {
        val now = System.currentTimeMillis() / 1000
        val cache = NoteCache(engine)
        val items = ArrayList<RulebookItem>()
        for (type in types.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()) {
            for (path in index.enumerate(SearchFilters(type = type), SCAN_CAP)) {
                if (path.startsWith("messy/")) continue
                val (_, doc) = cache.get(path) ?: continue
                if (doc.private || doc.type != type || !recallVisible(doc, now)) continue
                val masked = MarkdownChunker.stripPrivateSpans(doc.body)
                val summary = masked.lineSequence().map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !HEADING.matches(it) }
                    .orEmpty().take(SUMMARY_CHARS)
                items += RulebookItem(path, titleOf(path, doc, masked), type, stringOf(doc, "subject"), summary)
            }
        }
        items.sortWith(compareBy<RulebookItem> { it.type }.thenBy { it.title.lowercase() }.thenBy { it.path })
        return items.take(limit.coerceIn(0, RULEBOOK_MAX_LIMIT)) to queue(cache, index).size
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
