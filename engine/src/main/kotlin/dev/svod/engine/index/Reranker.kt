package dev.svod.engine.index

/**
 * Re-scores a small candidate set against the query with a cross-encoder, for a relevance lift over
 * the first-stage BM25 + kNN fusion. Strictly OPT-IN: the default [NoneReranker] is inactive and the
 * search path skips reranking entirely, so existing behavior is unchanged unless a reranker is
 * configured. Like [Embedder], implementations must do NO network I/O in their constructor (a cold
 * endpoint must never crash boot), and a failed call degrades to the fused order — it never errors a
 * search.
 */
interface Reranker {
    val model: String

    /** Canonical provider name for the settings view: `none` | `remote` (| `local-onnx` when added). */
    val provider: String

    /** Whether this reranker re-scores. False ⇒ search uses the fused order unchanged. */
    val isActive: Boolean get() = true

    /**
     * Relevance score for each doc against [query]; higher = more relevant. The result is aligned to
     * the input order (one score per doc). Throwing is allowed — the caller degrades to fused order.
     */
    fun rerank(query: String, docs: List<String>): List<Float>
}

/** The inactive baseline: no reranking. Search keeps the first-stage fused order. */
object NoneReranker : Reranker {
    override val model = "none"
    override val provider = "none"
    override val isActive = false
    override fun rerank(query: String, docs: List<String>): List<Float> =
        error("NoneReranker cannot rerank (reranking disabled)")
}
