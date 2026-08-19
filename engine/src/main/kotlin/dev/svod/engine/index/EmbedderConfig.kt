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
    /**
     * Whether the ONNX graph expects a `token_type_ids` input.
     *
     * This is a property of the EXPORT, not of the architecture: the e5-small export used here
     * requires it, while the e5-base export of the same model family does not, and passing it to a
     * graph that lacks it fails with a bare `Input mismatch, looking for: [input_ids,
     * attention_mask]`. Wrong either way, so it has to be per model rather than a constant.
     */
    val includeTokenTypes: Boolean = true,
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
    /** Max texts handed to the embedder in one call / per remote POST (background pass). */
    val batchSize: Int = 32,
    /** Per-request timeout for HTTP providers (generous, to ride out serverless cold starts). */
    val requestTimeoutSeconds: Int = 60,
) {
    /** Canonical provider name for the App API (local-onnx / local-ollama / remote-openai / none). */
    val providerName: String get() = when (provider) {
        EmbedderProvider.ONNX_LOCAL -> "local-onnx"
        EmbedderProvider.OLLAMA -> "local-ollama"
        EmbedderProvider.OPENAI -> "remote-openai"
        EmbedderProvider.NONE -> "none"
    }

    /** The effective model name for the active provider. */
    val modelName: String get() = when (provider) {
        EmbedderProvider.ONNX_LOCAL -> onnx.modelId
        EmbedderProvider.OLLAMA -> ollamaModel
        EmbedderProvider.OPENAI -> openaiModel
        EmbedderProvider.NONE -> "none"
    }

    /** The endpoint for HTTP providers; null for in-process providers (onnx/none). */
    val endpointOrNull: String? get() = when (provider) {
        EmbedderProvider.OLLAMA -> ollamaEndpoint
        EmbedderProvider.OPENAI -> openaiEndpoint
        else -> null
    }
}
