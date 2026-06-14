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
 * The optional bearer key is supplied already-resolved (a `Secrets` ref is resolved by the caller —
 * raw keys are never accepted over the App API). TEI/Infinity often need no key, so it is optional.
 *
 * No e5 query/passage prefixes are applied: OpenAI/Together models are symmetric and prefixes would
 * hurt them. The dimension is probed once at construction so Lucene can size the vector field.
 */
class OpenAiEmbedder(
    override val model: String = DEFAULT_MODEL,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val apiKey: String? = null,
) : Embedder {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override val dim: Int = embed(listOf("dimension probe")).first().size

    override fun embedPassages(texts: List<String>): List<FloatArray> =
        if (texts.isEmpty()) emptyList() else embed(texts)

    override fun embedQuery(text: String): FloatArray = embed(listOf(text)).first()

    private fun embed(inputs: List<String>): List<FloatArray> {
        val body = json.encodeToString(EmbedRequest.serializer(), EmbedRequest(model, inputs))
        val builder = HttpRequest.newBuilder(URI.create("${endpoint.trimEnd('/')}/v1/embeddings"))
            .timeout(Duration.ofMinutes(2))
            .header("Content-Type", "application/json")
        if (!apiKey.isNullOrBlank()) builder.header("Authorization", "Bearer $apiKey")
        val resp = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        check(resp.statusCode() == 200) { "openai-compatible embed failed: HTTP ${resp.statusCode()}: ${resp.body().take(300)}" }
        val parsed = json.decodeFromString(EmbedResponse.serializer(), resp.body())
        // The API may reorder; sort by index to keep result order aligned with inputs.
        return parsed.data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
    }

    companion object {
        const val DEFAULT_MODEL = "text-embedding-3-small"
        const val DEFAULT_ENDPOINT = "https://api.openai.com"

        /** True if the endpoint answers an embeddings request; used to gate integration tests. */
        fun isAvailable(model: String = DEFAULT_MODEL, endpoint: String = DEFAULT_ENDPOINT, apiKey: String? = null): Boolean = try {
            OpenAiEmbedder(model, endpoint, apiKey).dim > 0
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
