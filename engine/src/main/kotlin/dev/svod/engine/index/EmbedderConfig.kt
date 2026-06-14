package dev.svod.engine.index

import java.nio.file.Path

/** Selectable embedding provider. The default is in-process ONNX (no external server). */
enum class EmbedderProvider {
    /** In-process ONNX Runtime (DJL) running multilingual-e5-small. Default. */
    ONNX_LOCAL,

    /** Optional external Ollama server. */
    OLLAMA,

    /** OpenAI-compatible HTTP endpoint (`/v1/embeddings`): OpenAI, Together, RunPod TEI/Infinity. */
    OPENAI,

    /** No embeddings — BM25-only. The guaranteed baseline. */
    NONE,
}

/** Config for the in-process ONNX embedder. */
data class OnnxConfig(
    /** Logical model id, recorded in index metadata. */
    val modelId: String = "multilingual-e5-small",
    /**
     * Pre-placed model directory (containing the ONNX model + tokenizer.json). When null,
     * the model is downloaded on first run into `.svod/models/<modelId>/` and cached.
     */
    val localPath: Path? = null,
)

/** Top-level embedder configuration, provider-selected. */
data class EmbedderConfig(
    val provider: EmbedderProvider = EmbedderProvider.ONNX_LOCAL,
    val onnx: OnnxConfig = OnnxConfig(),
    val ollamaModel: String = OllamaEmbedder.DEFAULT_MODEL,
    val ollamaEndpoint: String = OllamaEmbedder.DEFAULT_ENDPOINT,
    /** OpenAI-compatible endpoint settings ([openaiApiKeyRef] is a `Secrets` ref, resolved at create). */
    val openaiEndpoint: String = OpenAiEmbedder.DEFAULT_ENDPOINT,
    val openaiModel: String = OpenAiEmbedder.DEFAULT_MODEL,
    val openaiApiKeyRef: String? = null,
    /** Cap of concurrent low-priority background embedding workers (keeps indexing off the CPU). */
    val maxThreads: Int = 2,
    /** Max texts handed to the embedder in one call (background pass). */
    val batchSize: Int = 32,
)
