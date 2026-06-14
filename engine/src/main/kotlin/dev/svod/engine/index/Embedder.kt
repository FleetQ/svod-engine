package dev.svod.engine.index

/**
 * Produces dense vector embeddings for chunks (passages) and queries.
 *
 * Pluggable: the index works with any provider, and with none at all. e5-family models are
 * asymmetric (passages and queries are prefixed "passage: " / "query: "); implementations
 * own that detail. [model] and [dim] are recorded in index metadata so a provider/model
 * swap is detected and triggers a reindex.
 *
 * Semantic retrieval is strictly OPT-IN over the BM25 baseline: when [isActive] is false
 * the index is lexical-only and [embedPassages]/[embedQuery] are never called.
 */
interface Embedder {
    val model: String

    /**
     * Vector dimension. For remote providers this may require a network probe — so it must NOT be
     * read on the boot path (use [knownDim]); read it only where a blocking probe is acceptable
     * (the /embedder/test endpoint, integration-availability checks).
     */
    val dim: Int

    /** Whether this embedder produces vectors. False ⇒ BM25-only; embed* must not be called. */
    val isActive: Boolean get() = dim > 0

    /**
     * Dimension if already known WITHOUT a network call (else 0). Boot/metadata code uses this so a
     * cold remote endpoint never blocks or crashes startup. Local providers know it eagerly; remote
     * providers learn it from the first successful embed.
     */
    fun knownDim(): Int = dim

    /** Embed document chunks. Order of the result matches the input. */
    fun embedPassages(texts: List<String>): List<FloatArray>

    /** Embed a search query. */
    fun embedQuery(text: String): FloatArray
}

/**
 * The guaranteed baseline: no embeddings, BM25-only. With this provider the vault is fully
 * searchable (lexical) and has zero external or model prerequisites.
 */
object NoneEmbedder : Embedder {
    override val model = "none"
    override val dim = 0
    override val isActive = false
    override fun embedPassages(texts: List<String>): List<FloatArray> = error("NoneEmbedder cannot embed (BM25-only mode)")
    override fun embedQuery(text: String): FloatArray = error("NoneEmbedder cannot embed (BM25-only mode)")
}
