package dev.svod.engine.index

import kotlinx.serialization.SerialName
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
    /**
     * How long Ollama keeps the model resident after a request. Ollama's server default is `5m`
     * (confirmed against a live server), so on a personal vault the first search of a session
     * routinely pays a full model reload.
     *
     * Measured reload cost, bge-m3 on Apple Silicon: range **0.93-2.39 s, median ~1.5 s** with the
     * model file already in the OS page cache (pooled n=9 over two unload→load runs), against
     * ~110 ms for a warm embed. A single fully-cold observation (first search of the day, model file
     * not recently read) came in at 6.0 s, but that is the tail, not the typical case — do not quote
     * it as the expected number.
     *
     * The trade: holding the model resident costs RAM for the idle window — measured via `/api/ps`,
     * **~0.6 GiB for bge-m3** (1.08 GiB on disk); [DEFAULT_MODEL] multilingual-e5-large is the larger
     * case at ~2.1 GiB. That buys away a ~1.5 s median stall on the first search after an idle
     * period, roughly **5x** a normal (cache-miss) semantic search at the low end and ~12-15x at the
     * high end. A modest, real UX fix, not a dramatic one — but sub-gigabyte residency on a personal
     * workstation makes it an easy trade.
     *
     * Note the reload is only ever paid on a cache MISS: a repeat query is served by
     * [CachingEmbedder] without touching Ollama at all, so that path never sees this.
     *
     * NB: omitting `keep_alive` does NOT reset an already-loaded model to the server default — the
     * previously-set window is retained. Whatever last touched the model decides its lifetime.
     */
    private val keepAlive: String = DEFAULT_KEEP_ALIVE,
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

    /** The serialized request body for [inputs]; extracted so a test can assert its shape. */
    internal fun requestBody(inputs: List<String>): String =
        json.encodeToString(EmbedRequest.serializer(), EmbedRequest(model, inputs, truncate = true, keepAlive = keepAlive))

    private fun embed(inputs: List<String>): List<FloatArray> {
        val body = requestBody(inputs)
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
        /** Ollama's own default is `5m`; long enough here that a normal working session never reloads. */
        const val DEFAULT_KEEP_ALIVE = "30m"
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 16_000L

        /** True if an Ollama server with [model] is reachable; used to gate integration tests. */
        fun isAvailable(model: String = DEFAULT_MODEL, endpoint: String = DEFAULT_ENDPOINT): Boolean = try {
            OllamaEmbedder(model, endpoint, maxRetries = 0).dim > 0
        } catch (_: Throwable) {
            false
        }
    }

    // truncate=true: a chunk longer than the model's context is truncated to fit rather than returning
    // HTTP 400 ("input length exceeds the context length"), which would otherwise abort the pass. The
    // chunker keeps chunks small enough that truncation rarely triggers and loses little when it does.
    //
    // NO default values here, deliberately: kotlinx.serialization omits a property that equals its
    // declared default, so defaults would silently drop these fields from the wire. `truncate` used to
    // have one and was never actually sent (harmless — Ollama also defaults it to true), but
    // `keep_alive` defaults to 5m server-side, so dropping it would undo the whole point of setting it.
    @Serializable
    private data class EmbedRequest(
        val model: String,
        val input: List<String>,
        val truncate: Boolean,
        @SerialName("keep_alive") val keepAlive: String,
    )

    @Serializable
    private data class EmbedResponse(val embeddings: List<List<Float>>)
}
