package dev.svod.engine.migrate

import dev.svod.engine.core.Author
import dev.svod.engine.core.BatchEntry
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.memory.frontmatterFences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/**
 * Imports a Mem0 memory export (a local JSON file) into Svod: one markdown note per memory at
 * `‹into›/‹mem0 id›.md`. The path depends only on the Mem0 id and the note text is built
 * deterministically, so a re-run is idempotent: identical notes are `unchanged` (no commit), and a
 * note that differs locally is `skipped`, never overwritten. Malformed entries are reported in
 * `skipped` as `entry i: reason` and do not abort the import.
 *
 * Accepted shapes: a top-level array, or an object holding a `results` or `memories` array.
 */
object Mem0Import {

    private const val CHUNK = 512
    private const val MAX_BYTES = 64L * 1024 * 1024
    private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    suspend fun import(
        source: Path,
        engine: SvodEngine,
        author: Author = Author("mem0-import", "import@svod.localhost"),
        into: String = "mem0",
    ): ObsidianImport.Result {
        // An unreadable export is a bad `source` (400 at the API), not a server error, and is not
        // reported as invalid JSON.
        val bytes = try {
            require(Files.size(source) <= MAX_BYTES) { "mem0 export is larger than 64 MiB" }
            Files.readAllBytes(source)
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("cannot read mem0 export: ${e.message}", e)
        }
        val root = try {
            // A UTF-8 BOM (Windows tools add one) is not part of the JSON.
            Json.parseToJsonElement(String(bytes, UTF_8).removePrefix(Char(0xFEFF).toString()))
        } catch (e: Exception) {
            throw IllegalArgumentException("mem0 export is not valid JSON", e)
        }
        val items: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["results"] as? JsonArray) ?: (root["memories"] as? JsonArray)
                ?: throw IllegalArgumentException("mem0 export must be an array or an object with a results or memories array")
            else -> throw IllegalArgumentException("mem0 export must be an array or an object with a results or memories array")
        }

        val prefix = into.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val skipped = ArrayList<String>()
        // Keyed case-insensitively: `A.md` and `a.md` are one file on macOS/Windows hosts the vault syncs to.
        val seen = HashSet<String>()
        val entries = ArrayList<Pending>()
        items.forEachIndexed { i, el ->
            val obj = el as? JsonObject ?: run { skipped += "entry $i: not an object"; return@forEachIndexed }
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: run { skipped += "entry $i: missing id"; return@forEachIndexed }
            if (!SAFE_ID.matches(id)) { skipped += "entry $i: unsafe id"; return@forEachIndexed }
            val memory = (obj["memory"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (memory.isNullOrBlank()) { skipped += "entry $i: blank memory"; return@forEachIndexed }
            if (!seen.add(id.lowercase())) { skipped += "entry $i: duplicate id"; return@forEachIndexed }
            entries += Pending(id, memory, obj)
        }

        val imported = ArrayList<String>()
        val unchanged = ArrayList<String>()
        // Note texts are built per chunk, so only one chunk of them is in memory at a time.
        for (chunk in entries.chunked(CHUNK)) {
            val batch = chunk.map { BatchEntry.Text("$prefix${it.id}.md", noteText(it.id, it.memory, it.obj)) }
            val r = engine.writeBatch(batch, author, "import: mem0, ${batch.size} memories")
            imported += r.written; unchanged += r.unchanged; skipped += r.skipped
        }
        return ObsidianImport.Result(imported.distinct().sorted(), unchanged.distinct().sorted(), skipped.distinct().sorted())
    }

    private class Pending(val id: String, val memory: String, val obj: JsonObject)

    private fun noteText(id: String, memory: String, obj: JsonObject): String {
        // Cut at 80 chars without splitting a surrogate pair (an emoji at the boundary).
        val title = memory.trim().lineSequence().first().trim().let { line ->
            val cut = line.take(80)
            if (cut.length < line.length && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
        }
        val fm = linkedMapOf<String, Any?>(
            "title" to title,
            "source" to "mem0",
            "mem0_id" to id,
        )
        str(obj["created_at"])?.let { fm["created"] = it }
        str(obj["updated_at"])?.let { fm["updated"] = it }
        for (k in listOf("user_id", "agent_id", "app_id", "run_id")) str(obj[k])?.let { fm["mem0_$k"] = it }
        val categories = (obj["categories"] as? JsonArray).orEmpty().mapNotNull { c ->
            str(c)?.trim()?.removePrefix("#")?.trim()?.replace(' ', '-')?.takeIf { it.isNotEmpty() }
        }
        fm["tags"] = (listOf("mem0") + categories).distinct()
        (obj["metadata"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { fm["mem0_metadata"] = plain(it) }
        val body = if (memory.endsWith("\n")) memory else "$memory\n"
        return frontmatterFences(fm) + body
    }

    /** A JSON scalar as a string (numbers and booleans too); null for JSON null, arrays and objects. */
    private fun str(el: JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

    private fun plain(el: JsonElement): Any? = when (el) {
        is JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().apply { el.forEach { (k, v) -> put(k, plain(v)) } }
        is JsonArray -> el.map { plain(it) }
        is JsonPrimitive -> when {
            el.isString -> el.content
            el.booleanOrNull != null -> el.booleanOrNull
            el.longOrNull != null -> el.longOrNull
            // An integer beyond Long stays exact rather than rounding through Double.
            else -> el.content.toBigIntegerOrNull() ?: el.doubleOrNull ?: el.content
        }
    }
}
