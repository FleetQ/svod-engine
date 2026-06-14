package dev.svod.engine.index

import java.nio.file.Path

/**
 * Builds the configured [Embedder]. This is the single place the provider model is
 * resolved; the rest of the engine depends only on the [Embedder] interface.
 *
 * `onnx-local` (DJL + ONNX Runtime) is loaded **reflectively** so a GraalVM native image stays
 * free of DJL: the class name is computed at run time (not a foldable constant), so the
 * closed-world analysis never reaches DJL/ONNX and the JVM-only native-library wall is avoided.
 * A native binary therefore serves `none` (BM25) and `ollama`; asking it for `onnx-local` fails
 * fast with a clear message. On the JVM/app-image the class is present and loads normally.
 * (See ADR-0015.)
 */
object Embedders {

    // Computed (not a compile-time constant) so native-image does NOT auto-register the class.
    private val ONNX_FQCN = listOf("dev.svod.engine.index", "OnnxLocalEmbedder").joinToString(".")

    /** @param vaultRoot used to locate `.svod/models/` for the on-first-run model cache. */
    fun create(config: EmbedderConfig, vaultRoot: Path): Embedder = when (config.provider) {
        EmbedderProvider.NONE -> NoneEmbedder
        EmbedderProvider.OLLAMA -> OllamaEmbedder(
            config.ollamaModel,
            config.ollamaEndpoint,
            java.time.Duration.ofSeconds(config.requestTimeoutSeconds.toLong()),
        )
        EmbedderProvider.OPENAI -> OpenAiEmbedder(
            config.openaiModel,
            config.openaiEndpoint,
            // API keys are Secrets references only (env:/file:/keychain:) — never raw over the API.
            config.openaiApiKeyRef?.takeIf { it.isNotBlank() }?.let { dev.svod.engine.security.Secrets.resolve(it) },
            java.time.Duration.ofSeconds(config.requestTimeoutSeconds.toLong()),
        )
        EmbedderProvider.ONNX_LOCAL -> loadOnnxLocal(config.onnx, vaultRoot.resolve(".svod").resolve("models"))
    }

    private fun loadOnnxLocal(onnx: OnnxConfig, modelsDir: Path): Embedder {
        val cls = try {
            Class.forName(ONNX_FQCN)
        } catch (_: ClassNotFoundException) {
            throw UnsupportedOperationException(
                "embedder provider 'onnx-local' is not available in this build (the native binary " +
                    "omits DJL/ONNX) — use the JVM app-image, or set embedder.provider to 'none' or 'ollama'."
            )
        }
        return cls.getMethod("load", OnnxConfig::class.java, Path::class.java).invoke(null, onnx, modelsDir) as Embedder
    }
}
