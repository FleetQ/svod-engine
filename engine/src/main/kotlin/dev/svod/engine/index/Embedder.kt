package dev.svod.engine.index

/**
 * Produces dense vector embeddings for chunks (passages) and queries.
 *
 * e5-family models are asymmetric: passages and queries are prefixed differently
 * ("passage: " / "query: "). Implementations own that detail so the rest of the index
 * does not care. [model] and [dim] are recorded in the index metadata so a model swap
 * can be detected and trigger a reindex.
 */
interface Embedder {
    val model: String
    val dim: Int

    /** Embed document chunks. Order of the result matches the input. */
    fun embedPassages(texts: List<String>): List<FloatArray>

    /** Embed a search query. */
    fun embedQuery(text: String): FloatArray
}
