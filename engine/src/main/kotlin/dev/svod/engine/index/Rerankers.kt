package dev.svod.engine.index

import java.nio.file.Path
import java.time.Duration

/** Selectable reranking provider. Default [NONE] keeps the first-stage fused order. */
enum class RerankerProvider { NONE, REMOTE, LOCAL_ONNX }

/**
 * Reranker configuration. [topK] bounds how many top fused candidates are re-scored per query
 * (the rest keep their fused order) — a cross-encoder call is O(topK), so this caps its cost.
 */
data class RerankerConfig(
    val provider: RerankerProvider = RerankerProvider.NONE,
    val endpoint: String = RemoteReranker.DEFAULT_ENDPOINT,
    val model: String = RemoteReranker.DEFAULT_MODEL,
    /** API key as a resolved secret (the factory resolves a Secrets ref); null for an open endpoint. */
    val apiKey: String? = null,
    val topK: Int = 50,
    val requestTimeoutSeconds: Int = 30,
    /** Model id + optional pre-placed directory for the `local-onnx` provider. */
    val onnx: OnnxConfig = OnnxConfig(modelId = OnnxLocalReranker.DEFAULT_MODEL),
)

/**
 * Builds the configured [Reranker]; the single place a rerank provider is resolved.
 *
 * `local-onnx` is loaded **reflectively** for the same reason as the ONNX embedder: the class name
 * is computed at run time, so a GraalVM native image's closed-world analysis never reaches
 * DJL/ONNX. A native binary therefore serves `none` and `remote` and fails fast on `local-onnx`.
 * (See ADR-0015 and [Embedders].)
 */
object Rerankers {

    // Computed (not a compile-time constant) so native-image does NOT auto-register the class.
    private val ONNX_FQCN = listOf("dev.svod.engine.index", "OnnxLocalReranker").joinToString(".")

    /** @param vaultRoot used to locate `.svod/models/` for the on-first-run model cache. */
    fun create(config: RerankerConfig, vaultRoot: Path): Reranker = when (config.provider) {
        RerankerProvider.NONE -> NoneReranker
        RerankerProvider.REMOTE -> RemoteReranker(
            config.model,
            config.endpoint,
            config.apiKey,
            Duration.ofSeconds(config.requestTimeoutSeconds.toLong()),
        )
        // A reranker that cannot load must not take the vault down with it: ranking is an
        // optimisation, search staying up is not. Degrade to the fused order, loudly in the log.
        RerankerProvider.LOCAL_ONNX -> try {
            loadOnnxLocal(config.onnx, vaultRoot.resolve(".svod").resolve("models"))
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(Rerankers::class.java)
                .warn("local-onnx reranker unavailable ({}); search continues with the fused order", e.toString())
            NoneReranker
        }
    }

    private fun loadOnnxLocal(onnx: OnnxConfig, modelsDir: Path): Reranker {
        val cls = try {
            Class.forName(ONNX_FQCN)
        } catch (_: ClassNotFoundException) {
            throw UnsupportedOperationException(
                "reranker provider 'local-onnx' is not available in this build (the native binary " +
                    "omits DJL/ONNX) — use the JVM app-image, or set reranker.provider to 'none' or 'remote'."
            )
        }
        return cls.getMethod("load", OnnxConfig::class.java, Path::class.java).invoke(null, onnx, modelsDir) as Reranker
    }
}
