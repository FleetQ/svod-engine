package dev.svod.engine.index

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real in-process ONNX embedder (multilingual-e5-small). Uses the pre-cached model dir when
 * present (and downloads otherwise via ModelManager); skips cleanly if neither is possible,
 * keeping CI hermetic.
 */
class OnnxEmbedderTest {

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (sqrt(na) * sqrt(nb))
    }

    private fun onnxConfigOrSkip(): OnnxConfig {
        val cache = Path.of(System.getProperty("user.home"), ".cache", "svod-models", "multilingual-e5-small")
        val ready = Files.isRegularFile(cache.resolve(ModelManager.MODEL_FILE)) &&
            Files.isRegularFile(cache.resolve(ModelManager.TOKENIZER_FILE))
        assumeTrue(ready, "e5-small model not cached at $cache — skipping ONNX test")
        return OnnxConfig(localPath = cache)
    }

    @Test
    fun `e5 small embeds 384-dim unit vectors with correct semantic ordering`() {
        val config = onnxConfigOrSkip()
        OnnxLocalEmbedder.load(config, Path.of("/unused")).use { e ->
            assertEquals(384, e.dim)

            val q = e.embedQuery("What is the capital of France?")
            val relevant = e.embedPassages(listOf("Paris is the capital city of France.")).first()
            val irrelevant = e.embedPassages(listOf("Bananas are a sweet yellow tropical fruit.")).first()

            // normalize check (L2 ~ 1)
            val norm = sqrt(q.fold(0.0) { acc, x -> acc + x * x })
            assertTrue(norm in 0.98..1.02, "query embedding must be L2-normalized, got |v|=$norm")

            val simRel = cosine(q, relevant)
            val simIrr = cosine(q, irrelevant)
            println("[e5] cos(capital?, Paris)=$simRel  cos(capital?, bananas)=$simIrr")

            // Regression pin: a direct answer scores high; unrelated scores clearly lower.
            assertTrue(simRel in 0.80..0.97, "relevant similarity out of expected band: $simRel")
            assertTrue(simRel - simIrr > 0.15, "relevant must clearly outrank irrelevant: $simRel vs $simIrr")
        }
    }

    @Test
    fun `prefixes and pooling give cross-lingual alignment`() {
        val config = onnxConfigOrSkip()
        OnnxLocalEmbedder.load(config, Path.of("/unused")).use { e ->
            val en = e.embedQuery("a small domestic cat that purrs")
            val ru = e.embedPassages(listOf("кошка — маленькое домашнее животное, которое мурлычет")).first()
            val unrelated = e.embedPassages(listOf("quantum chromodynamics and gluon fields")).first()
            val simCat = cosine(en, ru)
            val simOther = cosine(en, unrelated)
            println("[e5] cos(en-cat, ru-cat)=$simCat  cos(en-cat, physics)=$simOther")
            // e5-small has a high similarity floor; the cross-lingual pair must still win clearly.
            assertTrue(simCat in 0.75..0.92, "cross-lingual similarity out of band: $simCat")
            assertTrue(simCat > simOther + 0.05, "cross-lingual cat match must beat unrelated: $simCat vs $simOther")
        }
    }
}
