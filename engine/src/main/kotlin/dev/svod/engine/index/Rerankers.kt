package dev.svod.engine.index

import java.time.Duration

/** Selectable reranking provider. Default [NONE] keeps the first-stage fused order. */
enum class RerankerProvider { NONE, REMOTE }

/**
 * Reranker configuration. [topK] bounds how many top fused candidates are re-scored per query
 * (the rest keep their fused order) — a cross-encoder call is O(topK), so this caps its cost.
 */
data class RerankerConfig(
    val provider: RerankerProvider = RerankerProvider.NONE,
    val endpoint: String = RemoteReranker.DEFAULT_ENDPOINT,
    val model: String = RemoteReranker.DEFAULT_MODEL,
    /** API key as a resolved secret (the factory resolves a Secrets ref); null for an open endpoint. */
    val apiKey: String? = null,
    val topK: Int = 50,
    val requestTimeoutSeconds: Int = 30,
)

/** Builds the configured [Reranker]; the single place a rerank provider is resolved. */
object Rerankers {
    fun create(config: RerankerConfig): Reranker = when (config.provider) {
        RerankerProvider.NONE -> NoneReranker
        RerankerProvider.REMOTE -> RemoteReranker(
            config.model,
            config.endpoint,
            config.apiKey,
            Duration.ofSeconds(config.requestTimeoutSeconds.toLong()),
        )
    }
}
