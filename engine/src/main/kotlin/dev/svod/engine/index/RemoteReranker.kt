package dev.svod.engine.index

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [Reranker] backed by a Text-Embeddings-Inference-compatible `/rerank` endpoint (TEI, Infinity,
 * Jina, and most OpenAI-adjacent rerank servers): `POST {endpoint}/rerank {query, texts[]}` →
 * `[{index, score}, …]`. Construction does NO network I/O — a cold endpoint is tolerated; a failed
 * call throws and the caller degrades to the fused order. Requests use a generous, configurable
 * timeout and retry with backoff.
 */
class RemoteReranker(
    override val model: String,
    private val endpoint: String,
    private val apiKey: String? = null,
    private val requestTimeout: Duration = Duration.ofSeconds(30),
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
) : Reranker {

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override val isActive: Boolean = true

    override fun rerank(query: String, docs: List<String>): List<Float> {
        if (docs.isEmpty()) return emptyList()
        val body = buildJsonObject {
            put("query", query)
            // TEI uses `texts`; include `model` for servers that require it (Jina/Cohere ignore extras).
            put("texts", JsonArray(docs.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            if (model.isNotBlank() && model != "none") put("model", model)
        }
        val request = HttpRequest.newBuilder(URI.create("$endpoint/rerank"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), body)))
            .build()

        var lastError: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
                val code = resp.statusCode()
                if (code == 200) return parse(resp.body(), docs.size)
                if (code != 429 && code < 500) throw IllegalStateException("rerank failed: HTTP $code: ${resp.body().take(300)}")
                lastError = IllegalStateException("rerank HTTP $code")
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
        throw IllegalStateException("rerank failed after ${maxRetries + 1} attempts: ${lastError?.message}", lastError)
    }

    /**
     * Parse `[{index, score}, …]` (TEI) or `{results: [{index, relevance_score}, …]}` (Jina/Cohere)
     * back into scores aligned to the input order. Missing entries score 0 (sorted last).
     */
    private fun parse(bodyText: String, n: Int): List<Float> {
        val root = json.parseToJsonElement(bodyText)
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["results"] as? JsonArray ?: root["data"] as? JsonArray
                ?: throw IllegalStateException("unexpected rerank response shape")
            else -> throw IllegalStateException("unexpected rerank response shape")
        }
        val scores = FloatArray(n)
        for (el in array) {
            val o = el.jsonObject
            val idx = o["index"]?.jsonPrimitive?.int ?: continue
            val score = (o["score"] ?: o["relevance_score"])?.jsonPrimitive?.float ?: continue
            if (idx in 0 until n) scores[idx] = score
        }
        return scores.toList()
    }

    companion object {
        const val DEFAULT_MODEL = "BAAI/bge-reranker-base"
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:8080"
        const val DEFAULT_MAX_RETRIES = 2
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 8_000L
    }
}
