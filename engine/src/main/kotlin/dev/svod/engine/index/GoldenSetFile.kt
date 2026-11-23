package dev.svod.engine.index

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loader for a golden set kept OUTSIDE the repository — one JSON object per line:
 *
 * ```
 * {"q": "как се деплойва engine-ът", "gains": {"ops/deploy.md": 3, "ops/launchd.md": 1}}
 * ```
 *
 * Real golden sets are built from real notes, and those are private. Keeping the file out of git
 * is the only way the vault leg can use honest queries without personal content entering a public
 * repository. Blank lines and `#` comments are skipped so a human can annotate the file.
 */
object GoldenSetFile {

    /**
     * A `# Section ...` comment opens a reporting group that applies to every query below it, so a
     * hand-written file can carry its own breakdown (language direction, difficulty) without a
     * second format. Other `#` lines are plain comments.
     */
    fun load(path: Path): List<GoldenQuery> {
        require(Files.isRegularFile(path)) { "golden set not found: $path" }
        var group = ""
        val queries = mutableListOf<GoldenQuery>()
        Files.readAllLines(path).forEachIndexed { i, raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#") -> SECTION.find(line)?.let { group = it.groupValues[1].trim() }
                else -> queries += parse(line, i + 1).copy(group = group)
            }
        }
        require(queries.isNotEmpty()) { "golden set $path has no queries" }
        return queries
    }

    private val SECTION = Regex("^#\\s*Section\\s*\\d*\\s*[-–:]?\\s*(.+)$", RegexOption.IGNORE_CASE)

    private fun parse(line: String, lineNo: Int): GoldenQuery {
        val obj = try {
            Json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("golden set line $lineNo is not valid JSON: $line", e)
        }
        val text = obj["q"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("golden set line $lineNo has no \"q\"")
        val gains = obj["gains"]?.jsonObject
            ?.mapValues { (_, v) -> v.jsonPrimitive.content.toInt() }
            ?: throw IllegalArgumentException("golden set line $lineNo has no \"gains\"")
        require(gains.values.any { it > 0 }) { "golden set line $lineNo has no relevant note (all gains 0)" }
        return GoldenQuery(text, gains, why = "line $lineNo")
    }
}
