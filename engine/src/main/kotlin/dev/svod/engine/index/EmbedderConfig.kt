package dev.svod.engine.index

import java.nio.file.Path

/** Selectable embedding provider. The default is in-process ONNX (no external server). */
enum class EmbedderProvider {
    /** In-process ONNX Runtime (DJL) running multilingual-e5-small. Default. */
    ONNX_LOCAL,

    /** Optional external Ollama server. */
    OLLAMA,

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
)
