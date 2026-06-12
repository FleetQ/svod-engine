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
) : Embedder {
    val passageCalls = AtomicInteger(0)
    val queryCalls = AtomicInteger(0)

    override fun embedPassages(texts: List<String>): List<FloatArray> {
        passageCalls.addAndGet(texts.size)
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

    override fun close() {
        engine.close()
    }

    companion object {
        fun create(): IndexFixture = IndexFixture()
    }
}
