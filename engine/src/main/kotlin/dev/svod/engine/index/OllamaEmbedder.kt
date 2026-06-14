package dev.svod.engine.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [Embedder] backed by a local Ollama server (`/api/embed`). Default model is
 * multilingual-e5-large (1024-dim), which handles Latin + Cyrillic + CJK.
 *
 * Construction does NO network I/O — the dimension is learned lazily from the first successful embed
 * (or an explicit [dim] probe), so a stopped/cold Ollama can never crash boot. Requests use a
 * generous, configurable timeout and retry with backoff (an Ollama pulling a model on first use can
 * be slow).
 */
class OllamaEmbedder(
    override val model: String = DEFAULT_MODEL,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val requestTimeout: Duration = Duration.ofSeconds(120),
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val passagePrefix: String = "passage: ",
    private val queryPrefix: String = "query: ",
) : Embedder {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cachedDim = 0

    override val isActive: Boolean = true

    /** Blocking probe (network). Only call where that is acceptable (e.g. /embedder/test). */
    override val dim: Int
        get() {
            if (cachedDim == 0) embedQuery("dimension probe")
            return cachedDim
        }

    override fun knownDim(): Int = cachedDim

    override fun embedPassages(texts: List<String>): List<FloatArray> =
        if (texts.isEmpty()) emptyList() else embed(texts.map { passagePrefix + it }).also { recordDim(it) }

    override fun embedQuery(text: String): FloatArray = embed(listOf(queryPrefix + text)).also { recordDim(it) }.first()

    private fun recordDim(vecs: List<FloatArray>) {
        if (cachedDim == 0 && vecs.isNotEmpty()) cachedDim = vecs.first().size
    }

    private fun embed(inputs: List<String>): List<FloatArray> {
        val body = json.encodeToString(EmbedRequest.serializer(), EmbedRequest(model, inputs))
        val request = HttpRequest.newBuilder(URI.create("$endpoint/api/embed"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        var lastError: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
                val code = resp.statusCode()
                if (code == 200) {
                    return json.decodeFromString(EmbedResponse.serializer(), resp.body()).embeddings.map { it.toFloatArray() }
                }
                if (code != 429 && code < 500) {
                    throw IllegalStateException("ollama embed failed: HTTP $code: ${resp.body().take(300)}")
                }
                lastError = IllegalStateException("ollama embed HTTP $code")
            } catch (e: java.io.IOException) {
                lastError = e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); throw e
            }
            if (attempt < maxRetries) {
                val millis = (BASE_BACKOFF_MS * (1L shl attempt)).coerceAtMost(MAX_BACKOFF_MS)
                try { Thread.sleep(millis) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
            }
        }
        throw IllegalStateException("ollama embed failed after ${maxRetries + 1} attempts: ${lastError?.message}", lastError)
    }

    companion object {
        const val DEFAULT_MODEL = "zylonai/multilingual-e5-large"
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:11434"
        const val DEFAULT_MAX_RETRIES = 3
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 16_000L

        /** True if an Ollama server with [model] is reachable; used to gate integration tests. */
        fun isAvailable(model: String = DEFAULT_MODEL, endpoint: String = DEFAULT_ENDPOINT): Boolean = try {
            OllamaEmbedder(model, endpoint, maxRetries = 0).dim > 0
        } catch (_: Throwable) {
            false
        }
    }

    @Serializable
    private data class EmbedRequest(val model: String, val input: List<String>)

    @Serializable
    private data class EmbedResponse(val embeddings: List<List<Float>>)
}
