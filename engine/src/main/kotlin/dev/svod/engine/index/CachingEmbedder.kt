package dev.svod.engine.index

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Wraps an [Embedder] with a bounded LRU cache over [embedQuery].
 *
 * Query embedding dominates semantic-search latency: measured against a live local Ollama
 * (bge-m3, 1024-dim), a warm search took ~165 ms of which ~112 ms was the embed round-trip
 * and only ~40-50 ms the HNSW lookup. Repeat queries — retyping, paging, the ⌘K palette
 * re-firing on the same text — paid that ~112 ms again every time.
 *
 * Only QUERIES are cached. [embedPassages] delegates straight through: chunk texts are
 * effectively unique, so caching them would grow the heap and never hit.
 *
 * Cache invalidation is structural rather than explicit: [Embedders.create] builds a fresh
 * instance whenever the provider/model changes, so a stale vector from a previous model can
 * never be served.
 */
class CachingEmbedder(
    /**
     * The wrapped embedder. Public because provider identity is observable through this wrapper:
     * callers that need the concrete provider type must be able to unwrap rather than be defeated
     * by the decorator.
     */
    val delegate: Embedder,
    private val capacity: Int = DEFAULT_CAPACITY,
) : Embedder, AutoCloseable {

    private val lock = ReentrantLock()

    // accessOrder=true makes get() count as a use, which is what turns this into an LRU
    // rather than insertion-ordered eviction.
    private val cache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>): Boolean = size > capacity
    }

    override val model: String get() = delegate.model

    override val dim: Int get() = delegate.dim

    override val isActive: Boolean get() = delegate.isActive

    override val passagePrefix: String get() = delegate.passagePrefix

    override fun knownDim(): Int = delegate.knownDim()

    override fun embedPassages(texts: List<String>): List<FloatArray> = delegate.embedPassages(texts)

    override fun embedQuery(text: String): FloatArray {
        lock.withLock { cache[text] }?.let { return it.copyOf() }
        // Deliberately outside the lock: a slow/cold embedder must not block other queries.
        // Two callers racing on the same text embed twice and the second store wins — the
        // vectors are identical, so the only cost is one duplicated call.
        val vec = delegate.embedQuery(text)
        lock.withLock { cache[text] = vec }
        // Copy on the way out: the cached array is shared across every later hit, so handing
        // out the live instance would let one caller's mutation corrupt all of them.
        return vec.copyOf()
    }

    /** Entries currently held (tests/diagnostics). */
    fun size(): Int = lock.withLock { cache.size }

    /**
     * Releases the delegate's resources. Load-bearing: a provider swap disposes the old embedder
     * via `(previous as? AutoCloseable)` (see [IndexService.setEmbedder] and `EmbedderController`),
     * so a wrapper that were not itself [AutoCloseable] would silently strand
     * [OnnxLocalEmbedder]'s native ONNX Runtime session on every swap.
     */
    override fun close() {
        lock.withLock { cache.clear() }
        (delegate as? AutoCloseable)?.close()
    }

    companion object {
        /** Enough to cover a session's repeated queries; ~4 KB each at 1024 dims. */
        const val DEFAULT_CAPACITY = 256
    }
}
