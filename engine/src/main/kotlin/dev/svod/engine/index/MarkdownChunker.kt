package dev.svod.engine.index

import org.yaml.snakeyaml.Yaml
import java.security.MessageDigest

internal fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Frontmatter + body extracted from a markdown file. */
data class ParsedDoc(
    val frontmatter: Map<String, Any?>,
    val tags: List<String>,
    val title: String?,
    /** epoch seconds from frontmatter created/date, or null. */
    val created: Long?,
    /** epoch seconds from frontmatter modified/updated, or null. */
    val modified: Long?,
    val body: String,
    val chunks: List<Chunk>,
)

/** One indexable section of a document, at heading granularity. */
data class Chunk(
    val ordinal: Int,
    val heading: String,
    val text: String,
) {
    /** Stable hash of the embedding-relevant text; identical text ⇒ embedding reuse. */
    val contentHash: String = sha256Hex("$heading\n$text")
}

/**
 * Splits a markdown document into heading-level chunks and parses YAML frontmatter.
 *
 * Chunking: content before the first heading is the "preamble" chunk; each ATX heading
 * (`#`..`######`) starts a new chunk that runs until the next heading. This gives
 * section/article granularity that survives edits elsewhere in the file (so unchanged
 * sections keep their hash and skip re-embedding).
 */
object MarkdownChunker {

    private val FRONTMATTER = Regex("^\\uFEFF?---\\r?\\n(.*?)\\r?\\n---\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")

    /**
     * Max characters of body text per chunk. A long section (or heading-less note) is split into
     * several chunks so no single chunk overruns an embedder's context window — Ollama bge-m3 defaults
     * to num_ctx=2048 and returns HTTP 400 on a longer input, and e5-small caps at 512 tokens. ~2000
     * chars stays comfortably under ~1024 tokens even for multibyte/Cyrillic text (which tokenizes to
     * more tokens per char). Combined with the embedder's own truncate safety net, an oversized chunk
     * can no longer abort an embedding pass.
     */
    private const val MAX_CHUNK_CHARS = 2000

    fun parse(raw: String): ParsedDoc {
        val (fmText, body) = splitFrontmatter(raw)
        val fm: Map<String, Any?> = fmText?.let { parseYaml(it) } ?: emptyMap()

        val tags = extractTags(fm)
        val title = (fm["title"] as? String)?.trim()
        val created = firstEpoch(fm, "created", "date", "createdAt")
        val modified = firstEpoch(fm, "modified", "updated", "updatedAt")

        return ParsedDoc(fm, tags, title, created, modified, body, chunk(body))
    }

    private fun splitFrontmatter(raw: String): Pair<String?, String> {
        val m = FRONTMATTER.find(raw) ?: return null to raw
        return m.groupValues[1] to raw.substring(m.range.last + 1)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseYaml(text: String): Map<String, Any?> = try {
        (Yaml().load(text) as? Map<String, Any?>) ?: emptyMap()
    } catch (_: Exception) {
        emptyMap() // malformed frontmatter must not break indexing
    }

    private fun extractTags(fm: Map<String, Any?>): List<String> {
        val v = fm["tags"] ?: fm["tag"] ?: return emptyList()
        return when (v) {
            is List<*> -> v.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            is String -> v.split(',', ' ').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    private fun firstEpoch(fm: Map<String, Any?>, vararg keys: String): Long? {
        for (k in keys) {
            val v = fm[k] ?: continue
            epochOf(v)?.let { return it }
        }
        return null
    }

    private fun epochOf(v: Any?): Long? = when (v) {
        is java.util.Date -> v.time / 1000
        is Number -> v.toLong()
        is String -> runCatching { java.time.Instant.parse(v).epochSecond }
            .recoverCatching { java.time.LocalDate.parse(v).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() }
            .getOrNull()
        else -> null
    }

    private fun chunk(body: String): List<Chunk> {
        val lines = body.split("\n")
        val chunks = mutableListOf<Chunk>()
        var heading = ""
        val buf = StringBuilder()
        var ordinal = 0

        fun flush() {
            val text = buf.toString().trim()
            if (text.isNotEmpty() || heading.isNotEmpty()) {
                // Split an oversized section into budget-sized pieces; small sections stay as one chunk.
                val pieces = splitToBudget(text)
                if (pieces.isEmpty()) chunks.add(Chunk(ordinal++, heading, "")) // heading-only section
                else for (piece in pieces) chunks.add(Chunk(ordinal++, heading, piece))
            }
            buf.setLength(0)
        }

        for (line in lines) {
            val h = HEADING.find(line)
            if (h != null) {
                flush()
                heading = h.groupValues[2].trim()
            } else {
                buf.append(line).append('\n')
            }
        }
        flush()
        // A document with no headings and no text yields a single empty preamble; drop it.
        return chunks.ifEmpty { listOf(Chunk(0, "", body.trim())) }.filter { it.heading.isNotEmpty() || it.text.isNotEmpty() }
    }

    /**
     * Split [text] into pieces of at most [MAX_CHUNK_CHARS], breaking at paragraph boundaries first,
     * then (for an oversized single paragraph) at word boundaries, and only as a last resort mid-word
     * (a giant token like a URL or base64 blob). Returns an empty list for blank text.
     */
    private fun splitToBudget(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        fun emit() { if (cur.isNotBlank()) out.add(cur.toString().trim()); cur.setLength(0) }
        for (para in text.split(Regex("\\n{2,}"))) {
            if (para.isBlank()) continue
            if (cur.isNotEmpty() && cur.length + para.length + 2 > MAX_CHUNK_CHARS) emit()
            if (para.length > MAX_CHUNK_CHARS) {
                emit()
                for (w in splitWords(para)) out.add(w)
            } else {
                if (cur.isNotEmpty()) cur.append("\n\n")
                cur.append(para)
            }
        }
        emit()
        return out
    }

    /** Pack [para] into ≤[MAX_CHUNK_CHARS] pieces at word boundaries; hard-cuts a single oversized token. */
    private fun splitWords(para: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        fun emit() { if (cur.isNotBlank()) out.add(cur.toString()); cur.setLength(0) }
        for (word in para.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            if (cur.isNotEmpty() && cur.length + word.length + 1 > MAX_CHUNK_CHARS) emit()
            if (word.length > MAX_CHUNK_CHARS) {
                emit()
                var i = 0
                while (i < word.length) { out.add(word.substring(i, minOf(i + MAX_CHUNK_CHARS, word.length))); i += MAX_CHUNK_CHARS }
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(word)
            }
        }
        emit()
        return out
    }
}
