package dev.svod.engine.index

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Compares embedders on the one thing the real-vault eval showed broken: putting a query in one
 * language near a note written in the other.
 *
 * On the real vault, cross-lingual retrieval scored a flat 0.000, and the rank diagnostic showed the
 * correct note is not in the top 500 of 3384 for 11 of 13 cross-lingual queries. That rules out the
 * candidate window and points at the embedding itself. This ranks the correct note among EVERY note
 * in the vault, per model, so "a bigger embedder fixes it" becomes a measurement rather than a hope.
 *
 * Deliberately ranks against the whole vault: a small distractor set would flatter every model, the
 * way a 27-note synthetic corpus flattered the reranker.
 *
 * Opt-in: `-Dsvod.eval.vault`, `-Dsvod.eval.golden`, `-Dsvod.eval.embedders=multilingual-e5-small,multilingual-e5-base`
 */
class CrossLingualEmbedderTest {

    @Test
    fun `rank the correct note across the whole vault, per embedder`() {
        val vault = System.getProperty("svod.eval.vault")
        val golden = System.getProperty("svod.eval.golden")
        val models = System.getProperty("svod.eval.embedders")
        assumeTrue(vault != null && golden != null && models != null, "set -Dsvod.eval.vault, .golden and .embedders")

        val queries = GoldenSetFile.load(Path.of(golden))
        val root = Path.of(vault)
        // One vector per NOTE (its first 2000 characters), not per chunk: this measures whether the
        // note is reachable at all, without chunking as a confounder.
        val notes = Files.walk(root)
            .filter { it.toString().endsWith(".md") && !it.toString().contains("/.svod/") && !it.toString().contains("/.git/") }
            .toList()
            .map { root.relativize(it).toString() to Files.readString(it).take(2000) }
        println("xling: ${notes.size} notes, ${queries.size} queries")

        for (modelId in models.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            // `ollama:<model>` measures what a live engine configured for Ollama actually uses —
            // which is the whole point: an eval against a model nobody runs describes nothing.
            val embedder: Embedder? = if (modelId.startsWith("ollama:")) {
                val name = modelId.removePrefix("ollama:")
                if (OllamaEmbedder.isAvailable()) OllamaEmbedder(name, OllamaEmbedder.DEFAULT_ENDPOINT) else null
            } else {
                val dir = ModelManager.sharedCacheDir().resolve(modelId)
                // The e5-base export has no token_type_ids input; the e5-small one requires it.
                if (Files.isRegularFile(dir.resolve(ModelManager.MODEL_FILE))) {
                    OnnxLocalEmbedder.load(
                        OnnxConfig(modelId = modelId, localPath = dir, includeTokenTypes = !modelId.contains("base")),
                        Path.of("/unused"),
                    )
                } else null
            }
            if (embedder == null) { println("xling: $modelId unavailable — skipped"); continue }
            (embedder as? AutoCloseable ?: AutoCloseable {}).use { _ ->
                val vectors = notes.chunked(32).flatMap { batch -> embedder.embedPassages(batch.map { it.second }) }
                for (group in queries.mapNotNull { it.group.ifEmpty { null } }.distinct()) {
                    val ranks = queries.filter { it.group == group }.map { q ->
                        val qv = embedder.embedQuery(q.text)
                        val order = vectors.indices.sortedByDescending { cosine(qv, vectors[it]) }
                        order.indexOfFirst { notes[it].first in q.relevant } + 1
                    }
                    val hits = ranks.filter { it > 0 }
                    println(
                        "xling %-22s [%s] n=%d top10=%d top50=%d median=%s".format(
                            modelId, group.substringAfterLast('(').removeSuffix(")"), ranks.size,
                            hits.count { it <= 10 }, hits.count { it <= 50 },
                            hits.sorted().let { if (it.isEmpty()) "-" else it[it.size / 2].toString() },
                        ),
                    )
                }
            }
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (Math.sqrt(na) * Math.sqrt(nb))
    }
}
