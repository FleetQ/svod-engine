package dev.svod.engine.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [Embedder] backed by any OpenAI-compatible embeddings endpoint (`POST {endpoint}/v1/embeddings`):
 * OpenAI, Together, and self-hosted servers like RunPod TEI / Infinity. Lets a vault use a
 * high-quality model without paying local CPU/RAM for it.
 *
 * Construction does NO network I/O — the dimension is learned lazily from the first successful
 * embed (or an explicit [dim] probe), so a cold/unreachable endpoint can never crash boot. Requests
 * use a generous, configurable timeout and retry with exponential backoff to ride out serverless
 * cold starts; concurrency is kept low by the caller (worker pool) to avoid cold-start storms.
 *
 * The optional bearer key is supplied already-resolved (a `Secrets` ref is resolved by the caller —
 * raw keys are never accepted over the App API). No e5 query/passage prefixes are applied (OpenAI/
 * Together models are symmetric).
 */
class OpenAiEmbedder(
    override val model: String = DEFAULT_MODEL,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val apiKey: String? = null,
    private val requestTimeout: Duration = Duration.ofSeconds(60),
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    /** Max inputs per request; TEI's own default and the safe floor across embedding servers. */
    private val maxBatch: Int = DEFAULT_MAX_BATCH,
) : Embedder {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cachedDim = 0

    // Remote providers are active by configuration — do NOT gate on dim (which would probe).
    override val isActive: Boolean = true

    /** Blocking probe (network). Only call where that is acceptable (e.g. /embedder/test). */
    override val dim: Int
        get() {
            if (cachedDim == 0) embedQuery("dimension probe")
            return cachedDim
        }

    override fun knownDim(): Int = cachedDim

    override fun embedPassages(texts: List<String>): List<FloatArray> =
        if (texts.isEmpty()) emptyList() else embed(texts).also { recordDim(it) }

    override fun embedQuery(text: String): FloatArray = embed(listOf(text)).also { recordDim(it) }.first()

    private fun recordDim(vecs: List<FloatArray>) {
        if (cachedDim == 0 && vecs.isNotEmpty()) cachedDim = vecs.first().size
    }

    private fun embed(inputs: List<String>): List<FloatArray> {
        // TEI answers HTTP 413 above its max client batch (default 32) — the failure that silently
        // disabled the reranker. IndexService already caps its own batches at 32, so this is a net
        // rather than a live fix: it makes the limit the transport's business, so a future caller
        // embedding an unbatched list degrades to more requests instead of a hard 413.
        if (inputs.size > maxBatch) return inputs.chunked(maxBatch).flatMap { embed(it) }
        val body = json.encodeToString(EmbedRequest.serializer(), EmbedRequest(model, inputs))
        val builder = HttpRequest.newBuilder(URI.create("${endpoint.trimEnd('/')}/v1/embeddings"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            // RunPod (and other Cloudflare-fronted hosts) answer 403 "error code: 1010" to the JDK
            // client's default `Java-http-client/NN` agent. Measured against a live RunPod TEI pod.
            .header("User-Agent", USER_AGENT)
        if (!apiKey.isNullOrBlank()) builder.header("Authorization", "Bearer $apiKey")
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()

        var lastError: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
                val code = resp.statusCode()
                if (code == 200) {
                    val parsed = json.decodeFromString(EmbedResponse.serializer(), resp.body())
                    // The API may reorder; sort by index to keep result order aligned with inputs.
                    return parsed.data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
                }
                // 429 / 5xx are transient (cold start, rate limit) → retry; 4xx is fatal → stop.
                if (code != 429 && code < 500) {
                    throw IllegalStateException("openai-compatible embed failed: HTTP $code: ${resp.body().take(300)}")
                }
                lastError = IllegalStateException("openai-compatible embed HTTP $code: ${resp.body().take(200)}")
            } catch (e: java.io.IOException) {
                lastError = e // connect/read timeout, connection refused — typical cold start
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            if (attempt < maxRetries) backoff(attempt)
        }
        throw IllegalStateException("openai-compatible embed failed after ${maxRetries + 1} attempts: ${lastError?.message}", lastError)
    }

    private fun backoff(attempt: Int) {
        val millis = (BASE_BACKOFF_MS * (1L shl attempt)).coerceAtMost(MAX_BACKOFF_MS)
        try { Thread.sleep(millis) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
    }

    companion object {
        /**
         * Cloudflare-fronted inference hosts (RunPod's proxy among them) reject the JDK
         * client's default Java-http-client agent with 403 "error code: 1010". Sending a
         * conventional agent is what makes those endpoints reachable at all. Measured against
         * a live RunPod TEI pod: 403 without it, 200 with it.
         */
        const val USER_AGENT = "Svod/1.0 (+https://svod.dev)"

        const val DEFAULT_MODEL = "text-embedding-3-small"
        const val DEFAULT_ENDPOINT = "https://api.openai.com"
        const val DEFAULT_MAX_BATCH = 32
        const val DEFAULT_MAX_RETRIES = 3
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 16_000L

        /** True if the endpoint answers an embeddings request; used to gate integration tests. */
        fun isAvailable(model: String = DEFAULT_MODEL, endpoint: String = DEFAULT_ENDPOINT, apiKey: String? = null): Boolean = try {
            OpenAiEmbedder(model, endpoint, apiKey, maxRetries = 0).dim > 0
        } catch (_: Throwable) {
            false
        }
    }

    @Serializable
    private data class EmbedRequest(val model: String, val input: List<String>)

    @Serializable
    private data class EmbedData(val embedding: List<Float>, val index: Int = 0)

    @Serializable
    private data class EmbedResponse(val data: List<EmbedData>)
}
