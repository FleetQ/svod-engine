package dev.svod.engine.memory

import dev.svod.engine.graphrag.OllamaSummaryLlm
import dev.svod.engine.graphrag.SummaryLlm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration

/** One candidate memory a model found in a transcript. [type] is one of [MemoryExtractor.TYPES]. */
data class ExtractedMemory(val content: String, val type: String, val subject: String? = null)

/**
 * Turns a conversation transcript into candidate memories for the `extract_memories` tool. The
 * candidates are NOT trusted: the caller runs each through the `remember` gate and stores it
 * `provisional`, so a person reviews it before recall can see it.
 */
interface MemoryExtractor {
    val isActive: Boolean

    /** Candidates, or null when the model was unavailable or its output was unparseable. Must not throw. */
    suspend fun extract(transcript: String, maxMemories: Int): List<ExtractedMemory>?

    companion object {
        val TYPES = setOf("policy", "preference", "fact", "episode")
    }
}

/** The default: no model configured, the tool answers "not configured". */
object NoneMemoryExtractor : MemoryExtractor {
    override val isActive = false
    override suspend fun extract(transcript: String, maxMemories: Int): List<ExtractedMemory>? = null
}

/** [MemoryExtractor] over a [SummaryLlm] (the same local-model client the thematic graph uses). */
class LlmMemoryExtractor(private val llm: SummaryLlm) : MemoryExtractor {
    override val isActive: Boolean get() = llm.isActive

    override suspend fun extract(transcript: String, maxMemories: Int): List<ExtractedMemory>? {
        val raw = try {
            llm.summarise(transcript, system = systemPrompt(maxMemories))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return parseCandidates(raw, maxMemories)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun systemPrompt(maxMemories: Int): String =
            "You read a finished conversation transcript and extract durable memories worth keeping long term: " +
                "rules or policies the user set, stable preferences, lasting facts about people, projects or systems, " +
                "and notable events. Skip small talk, temporary state, guesses and anything the transcript does not state. " +
                "Never include passwords, tokens or other secrets. Answer ONLY with a JSON array of at most $maxMemories " +
                "objects, each {\"content\": one self-contained sentence, \"type\": \"policy\"|\"preference\"|\"fact\"|\"episode\", " +
                "\"subject\": optional short topic}. Answer [] when there is nothing durable."

        /** The candidates in [raw] model output, at most [maxMemories]; null when no JSON array can be read. */
        internal fun parseCandidates(raw: String, maxMemories: Int): List<ExtractedMemory>? {
            val text = raw.replace(Regex("```[a-zA-Z]*"), "")
            val start = text.indexOf('[')
            val end = text.lastIndexOf(']')
            if (start < 0 || end <= start) return null
            val array = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull() as? JsonArray
                ?: return null
            return array.asSequence()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { o ->
                    val content = o.string("content")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val type = o.string("type")?.trim()?.lowercase()?.takeIf { it in MemoryExtractor.TYPES } ?: "fact"
                    ExtractedMemory(content, type, o.string("subject")?.trim()?.takeIf { it.isNotEmpty() })
                }
                .take(maxMemories.coerceAtLeast(0))
                .toList()
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

enum class MemoryExtractionProvider { NONE, OLLAMA }

/** Extraction-model configuration, resolved from [dev.svod.engine.lifecycle.SvodConfig.toMemoryExtractorConfig]. */
data class MemoryExtractionConfig(
    val provider: MemoryExtractionProvider = MemoryExtractionProvider.NONE,
    val model: String = OllamaSummaryLlm.DEFAULT_MODEL,
    val endpoint: String = OllamaSummaryLlm.DEFAULT_ENDPOINT,
    val timeoutSeconds: Int = 120,
    val maxTranscriptChars: Int = 20000,
)

object MemoryExtractors {
    /** Constructing the Ollama client does no network I/O. */
    fun create(config: MemoryExtractionConfig): MemoryExtractor = when (config.provider) {
        MemoryExtractionProvider.NONE -> NoneMemoryExtractor
        MemoryExtractionProvider.OLLAMA -> LlmMemoryExtractor(
            OllamaSummaryLlm(config.model, config.endpoint, Duration.ofSeconds(config.timeoutSeconds.toLong())),
        )
    }
}
