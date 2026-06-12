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
 * The dimension is probed once at construction with a tiny request, so Lucene can size
 * the vector field correctly before any document is indexed.
 */
class OllamaEmbedder(
    override val model: String = DEFAULT_MODEL,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val passagePrefix: String = "passage: ",
    private val queryPrefix: String = "query: ",
) : Embedder {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override val dim: Int = embed(listOf("query: dimension probe")).first().size

    override fun embedPassages(texts: List<String>): List<FloatArray> =
        if (texts.isEmpty()) emptyList() else embed(texts.map { passagePrefix + it })

    override fun embedQuery(text: String): FloatArray = embed(listOf(queryPrefix + text)).first()

    private fun embed(inputs: List<String>): List<FloatArray> {
        val body = json.encodeToString(EmbedRequest.serializer(), EmbedRequest(model, inputs))
        val req = HttpRequest.newBuilder(URI.create("$endpoint/api/embed"))
            .timeout(Duration.ofMinutes(2))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(resp.statusCode() == 200) { "ollama embed failed: HTTP ${resp.statusCode()}: ${resp.body().take(300)}" }
        val parsed = json.decodeFromString(EmbedResponse.serializer(), resp.body())
        return parsed.embeddings.map { it.toFloatArray() }
    }

    companion object {
        const val DEFAULT_MODEL = "zylonai/multilingual-e5-large"
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:11434"

        /** True if an Ollama server with [model] is reachable; used to gate integration tests. */
        fun isAvailable(model: String = DEFAULT_MODEL, endpoint: String = DEFAULT_ENDPOINT): Boolean = try {
            OllamaEmbedder(model, endpoint).dim > 0
        } catch (_: Throwable) {
            false
        }
    }

    @Serializable
    private data class EmbedRequest(val model: String, val input: List<String>)

    @Serializable
    private data class EmbedResponse(val embeddings: List<List<Float>>)
}
