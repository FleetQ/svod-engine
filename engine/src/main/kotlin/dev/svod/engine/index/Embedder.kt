package dev.svod.engine.index

/**
 * Produces dense vector embeddings for chunks (passages) and queries.
 *
 * Pluggable: the index works with any provider, and with none at all. e5-family models are
 * asymmetric (passages and queries are prefixed "passage: " / "query: "); implementations
 * own that detail via [passagePrefix]. [model], [dim] and [passagePrefix] are recorded in index
 * metadata so a provider/model/prefix swap is detected and triggers a reindex.
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

    /**
     * The prefix this model prepends to indexed passages, or "" for a model trained without one.
     *
     * Recorded in index metadata, because it changes the vectors as surely as changing the model
     * does: an index built with "passage: " cannot be searched by queries embedded without it.
     * Exposed on the interface rather than kept private to each implementation for exactly that
     * reason — [IndexMeta] has to see it.
     */
    val passagePrefix: String get() = ""

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

/**
 * Which input prefixes a model expects.
 *
 * e5 is trained asymmetrically and needs `query: ` / `passage: `; BGE — including bge-m3 — is
 * trained without any instruction, and its own README states plainly that "BGE-M3 does not require
 * adding instructions to queries" (FlagEmbedding, research/BGE_M3/README.md). Feeding a model text
 * shaped unlike anything it was trained on is not a harmless no-op.
 *
 * Defaulting to "no prefix" for unknown models is the safe direction: a model that wanted a prefix
 * loses some accuracy, while a model that did not want one is fed text it was never trained on.
 */
object ModelPrefixes {
    const val E5_QUERY = "query: "
    const val E5_PASSAGE = "passage: "

    /** True for the e5 family (`multilingual-e5-small`, `intfloat/multilingual-e5-large`, ...). */
    fun isE5(model: String): Boolean = model.contains("e5", ignoreCase = true)

    fun queryPrefix(model: String): String = if (isE5(model)) E5_QUERY else ""

    fun passagePrefix(model: String): String = if (isE5(model)) E5_PASSAGE else ""
}
