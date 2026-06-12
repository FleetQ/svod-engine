package dev.svod.engine.index

import java.nio.file.Path

/**
 * Builds the configured [Embedder]. This is the single place the provider model is
 * resolved; the rest of the engine depends only on the [Embedder] interface.
 */
object Embedders {

    /** @param vaultRoot used to locate `.svod/models/` for the on-first-run model cache. */
    fun create(config: EmbedderConfig, vaultRoot: Path): Embedder = when (config.provider) {
        EmbedderProvider.NONE -> NoneEmbedder
        EmbedderProvider.OLLAMA -> OllamaEmbedder(config.ollamaModel, config.ollamaEndpoint)
        EmbedderProvider.ONNX_LOCAL -> OnnxLocalEmbedder.load(config.onnx, vaultRoot.resolve(".svod").resolve("models"))
    }
}
