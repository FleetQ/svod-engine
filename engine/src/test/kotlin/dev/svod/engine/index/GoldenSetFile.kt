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

    fun load(path: Path): List<GoldenQuery> {
        require(Files.isRegularFile(path)) { "golden set not found: $path" }
        val queries = Files.readAllLines(path)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapIndexed { i, line -> parse(line, i + 1) }
        require(queries.isNotEmpty()) { "golden set $path has no queries" }
        return queries
    }

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
