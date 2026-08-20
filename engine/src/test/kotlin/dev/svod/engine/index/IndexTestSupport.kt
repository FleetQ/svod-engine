package dev.svod.engine.index

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

val INDEXER = Author("indexer-test", "idx@svod.test")

/**
 * Deterministic, dependency-free embedder for hermetic tests. A normalized bag-of-words
 * hash: texts sharing tokens get similar vectors, so kNN is meaningful, while the result
 * is fully reproducible and needs no Ollama. It also counts how many chunks were embedded,
 * which is how we assert incremental indexing only touches changed chunks.
 */
class FakeEmbedder(
    override val model: String,
    override val dim: Int = 64,
    private val delayMs: Long = 0,
    override val passagePrefix: String = "",
) : Embedder {
    val passageCalls = AtomicInteger(0)
    val queryCalls = AtomicInteger(0)
    /** Number of texts in each embedPassages call (to assert cross-file batching). */
    val batchSizes = java.util.concurrent.CopyOnWriteArrayList<Int>()

    override fun embedPassages(texts: List<String>): List<FloatArray> {
        passageCalls.addAndGet(texts.size)
        batchSizes.add(texts.size)
        if (delayMs > 0) Thread.sleep(delayMs)
        return texts.map { vec(it) }
    }

    override fun embedQuery(text: String): FloatArray {
        queryCalls.incrementAndGet()
        return vec(text)
    }

    private fun vec(text: String): FloatArray {
        val v = FloatArray(dim)
        for (t in text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }) {
            val h = t.hashCode()
            for (j in 0 until 3) v[Math.floorMod(h * 31 + j, dim)] += 1f
        }
        v[0] += 0.001f // guarantee non-zero for cosine similarity
        var norm = 0.0
        for (x in v) norm += (x * x).toDouble()
        norm = Math.sqrt(norm)
        if (norm > 0) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
        return v
    }
}

/** Temp vault + engine; index directory lives under the gitignored `.svod/`. */
class IndexFixture : AutoCloseable {
    val root: Path = Files.createTempDirectory("svod-index-test-")
    val indexDir: Path = root.resolve(".svod").resolve("index")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine: SvodEngine = SvodEngine.open(root, scope)

    suspend fun seed(path: String, content: String) {
        engine.write(path, content, expectedRevision = null, author = INDEXER)
    }

    fun newIndex(embedder: Embedder): IndexService = IndexService(root, indexDir, embedder).start()

    /**
     * A second index over the same vault, with its own directory and reranker. Lets one test
     * compare reranked against un-reranked results over an identical corpus — the reranker is
     * constructor-injected and cannot be swapped on a live index.
     */
    fun newIndex(embedder: Embedder, reranker: Reranker, dirName: String): IndexService =
        IndexService(root, root.resolve(".svod").resolve(dirName), embedder, reranker = reranker).start()

    override fun close() {
        engine.close()
    }

    companion object {
        fun create(): IndexFixture = IndexFixture()
    }
}

/** The pre-cached e5-small config, or null when the model isn't available (test skips). */
fun cachedE5ConfigOrNull(): OnnxConfig? {
    val cache = ModelManager.sharedCacheDir().resolve("multilingual-e5-small")
    val ready = Files.isRegularFile(cache.resolve(ModelManager.MODEL_FILE)) &&
        Files.isRegularFile(cache.resolve(ModelManager.TOKENIZER_FILE))
    return if (ready) OnnxConfig(localPath = cache) else null
}

/**
 * A hermetic embedder with actual semantic signal: a hashed bag of words, L2-normalised, so texts
 * sharing vocabulary get a high cosine and unrelated texts do not.
 *
 * [FakeEmbedder] hashes the whole string, which makes its vectors pure noise — fine for counting
 * embed calls, useless for measuring retrieval. Leg P used it and therefore could not gate fusion
 * quality at all: its HYBRID floor was a number about BM25 surviving noise, and any change that
 * trusted the semantic leg more was guaranteed to "fail" it regardless of merit. That is the same
 * blind-instrument failure as the reranker that silently never ran.
 *
 * Deliberately weak — no stemming, no idf, no subword handling — because it must stay a *pipeline*
 * gate that runs in CI with no model on disk. Leg S remains the real-model quality measurement.
 */
class BagOfWordsEmbedder(override val dim: Int = 64) : Embedder {
    val passageCalls = java.util.concurrent.atomic.AtomicInteger(0)

    override fun embedPassages(texts: List<String>): List<FloatArray> {
        passageCalls.addAndGet(texts.size)
        return texts.map { vec(it) }
    }

    override fun embedQuery(text: String): FloatArray = vec(text)

    override val model: String = "bag-of-words-test"

    private fun vec(text: String): FloatArray {
        val v = FloatArray(dim)
        for (token in text.lowercase().split(TOKEN).filter { it.length > 2 }) {
            // Two slots per token: one for the whole token, one for its 3-char prefix, so a
            // Bulgarian inflection ("бекъпа" vs "бекъп") still lands on a shared dimension.
            v[Math.floorMod(token.hashCode(), dim)] += 1f
            v[Math.floorMod(token.take(3).hashCode(), dim)] += 0.5f
        }
        val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    private companion object {
        val TOKEN = Regex("[^\\p{L}\\p{N}]+")
    }
}
