package dev.svod.engine.graphrag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Generates a short title + summary for one community. **The only place the engine consults a model
 * for text, and only at build time** — no query path may ever call this (guarded by test D1).
 *
 * [summarise] returns null rather than throwing: a missing or broken summariser must degrade the
 * feature (communities keep machine-generated labels), never fail the build. This mirrors how
 * `semanticLeg` degrades to keyword when a query embed fails.
 */
interface SummaryLlm {
    val provider: String
    val model: String
    val isActive: Boolean

    /**
     * Returns the raw model output, or null if unavailable. Must not throw.
     *
     * [system] is kept OUT of [prompt] on purpose: a small model handed 12k characters of source
     * documents will otherwise treat a leading instruction as more document and continue writing it.
     */
    suspend fun summarise(prompt: String, system: String? = null): String?
}

/** The guaranteed baseline: no summaries. Communities still exist, labelled from their members. */
object NoneSummaryLlm : SummaryLlm {
    override val provider = "none"
    override val model = "none"
    override val isActive = false
    override suspend fun summarise(prompt: String, system: String?): String? = null
}

/**
 * [SummaryLlm] backed by a local Ollama server (`/api/generate`). Construction does no network I/O,
 * so a stopped Ollama can never affect startup — an unreachable server simply yields null summaries.
 *
 * `keep_alive` matches the embedder's rationale: a build issues one call per community back to back,
 * so paying a model reload between them would dominate the wall time.
 */
class OllamaSummaryLlm(
    override val model: String = DEFAULT_MODEL,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val requestTimeout: Duration = Duration.ofSeconds(120),
    private val keepAlive: String = DEFAULT_KEEP_ALIVE,
) : SummaryLlm {

    override val provider = "ollama"
    override val isActive = true

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun summarise(prompt: String, system: String?): String? {
        val body = json.encodeToString(
            GenerateRequest.serializer(),
            GenerateRequest(model = model, prompt = prompt, system = system, keepAlive = keepAlive),
        )
        val req = HttpRequest.newBuilder(URI.create("${endpoint.trimEnd('/')}/api/generate"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() !in 200..299) {
                System.err.println("graph summary: ollama HTTP ${res.statusCode()}")
                return null
            }
            json.decodeFromString(GenerateResponse.serializer(), res.body()).response.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // Contained on purpose: a summariser failure degrades to a labelled community (test D5).
            System.err.println("graph summary: ollama call failed: ${e.message}")
            null
        }
    }

    @Serializable
    private data class GenerateRequest(
        val model: String,
        val prompt: String,
        val system: String? = null,
        val stream: Boolean = false,
        @kotlinx.serialization.SerialName("keep_alive") val keepAlive: String,
        val options: Options = Options(),
    ) {
        @Serializable
        data class Options(
            /** Low but non-zero: summaries should be stable across rebuilds without being degenerate. */
            val temperature: Double = 0.2,
        )
    }

    @Serializable
    private data class GenerateResponse(val response: String = "")

    companion object {
        const val DEFAULT_MODEL = "qwen2.5:7b-instruct"
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:11434"
        const val DEFAULT_KEEP_ALIVE = "30m"
    }
}

/** Selectable summary provider. Default [NONE] keeps the engine LLM-free. */
enum class SummaryProvider { NONE, OLLAMA }

/** Summary-model configuration, resolved from [dev.svod.engine.lifecycle.SvodConfig]. */
data class SummaryLlmConfig(
    val provider: SummaryProvider = SummaryProvider.NONE,
    val model: String = OllamaSummaryLlm.DEFAULT_MODEL,
    val endpoint: String = OllamaSummaryLlm.DEFAULT_ENDPOINT,
    val requestTimeoutSeconds: Int = 120,
)

/** Builds the configured [SummaryLlm]; the single place a summary provider is resolved. */
object SummaryLlms {
    fun create(config: SummaryLlmConfig): SummaryLlm = when (config.provider) {
        SummaryProvider.NONE -> NoneSummaryLlm
        SummaryProvider.OLLAMA -> OllamaSummaryLlm(
            config.model,
            config.endpoint,
            Duration.ofSeconds(config.requestTimeoutSeconds.toLong()),
        )
    }
}
