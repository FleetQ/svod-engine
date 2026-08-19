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

    private val log = org.slf4j.LoggerFactory.getLogger(Rerankers::class.java)

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
        // Loaded in the BACKGROUND. The first run downloads a ~471 MB model, and doing that on the
        // vault-open path would turn a ~13 s cold start into minutes of nothing. Until it is ready
        // (or if it never becomes ready) the reranker reports inactive and search serves the fused
        // order — ranking is an optimisation, search staying up is not.
        RerankerProvider.LOCAL_ONNX -> LazyReranker(config.model, OnnxLocalReranker.PROVIDER) {
            loadOnnxLocal(config.onnx, vaultRoot.resolve(".svod").resolve("models"))
        }
    }

    /**
     * Loads a reranker on a background thread and stays INACTIVE until it is ready.
     *
     * [isActive] is what the search path checks, so an unready (or permanently failed) reranker
     * simply means results keep their fused order — no exception per query, no boot delay, and no
     * user-visible failure beyond a log line. The settings view still reports the intended provider
     * and model, with `active: false`, which is what makes "still downloading" distinguishable from
     * "switched off".
     */
    private class LazyReranker(
        override val model: String,
        override val provider: String,
        loader: () -> Reranker,
    ) : Reranker, AutoCloseable {

        @Volatile private var delegate: Reranker? = null
        @Volatile private var closed = false

        private val loading = Thread({
            try {
                val loaded = loader()
                if (closed) loaded.let { (it as? AutoCloseable)?.close() } else delegate = loaded
            } catch (e: Exception) {
                log.warn("local-onnx reranker unavailable ({}); search continues with the fused order", e.toString())
            }
        }, "svod-reranker-load").apply { isDaemon = true; start() }

        override val isActive: Boolean get() = delegate != null

        override fun rerank(query: String, docs: List<String>): List<Float> =
            (delegate ?: error("reranker is still loading")).rerank(query, docs)

        override fun close() {
            closed = true
            // Brief join only: a download in flight must not hold vault close hostage. The `closed`
            // flag is what stops a late arrival from leaking — the loader closes it instead.
            runCatching { loading.join(2000) }
            (delegate as? AutoCloseable)?.let { runCatching { it.close() } }
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
