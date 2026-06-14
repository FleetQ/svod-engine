package dev.svod.engine.lifecycle

import dev.svod.engine.api.EmbedderControl
import dev.svod.engine.index.Embedders
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runtime embedder control for the App API. The embedder is a global engine setting (one provider
 * for every vault), so [apply] rebuilds the embedder for ALL vaults and persists the change to the
 * config file (when a path is known). API keys are accepted only as `Secrets` references — a raw key
 * over the wire is rejected ([EmbedderControl.InvalidSpec] ⇒ 422).
 */
class EmbedderController(
    private val vaults: VaultManager,
    private val configPath: Path?,
    initialConfig: SvodConfig,
) : EmbedderControl {

    @Volatile
    private var config = initialConfig

    override fun descriptor(vaultId: String): EmbedderControl.EmbedderDescriptor {
        val ec = config.toEmbedderConfig()
        val dim = vaults.context(vaultId)?.index?.indexedDim() ?: 0
        return EmbedderControl.EmbedderDescriptor(ec.providerName, ec.modelName, ec.endpointOrNull, dim)
    }

    override fun apply(vaultId: String, spec: EmbedderControl.EmbedderSpec): EmbedderControl.EmbedderDescriptor {
        val merged = merge(config.embedder, spec)
        val updated = config.copy(embedder = merged)
        val errors = updated.validate()
        if (errors.isNotEmpty()) throw EmbedderControl.InvalidSpec(errors.joinToString("; "))

        val ec = updated.toEmbedderConfig()
        // Build first (probes remote endpoints) so a bad spec fails before we touch any vault.
        for (vc in vaults.contexts()) {
            val embedder = Embedders.create(ec, vc.engine.root)
            vc.index.setEmbedder(embedder)
        }
        config = updated
        configPath?.let { Files.writeString(it, SvodConfig.toJson(updated)) }
        return descriptor(vaultId)
    }

    override fun test(vaultId: String, spec: EmbedderControl.EmbedderSpec): EmbedderControl.ProbeResult {
        return try {
            val ec = config.copy(embedder = merge(config.embedder, spec)).toEmbedderConfig()
            val root = (vaults.context(vaultId) ?: vaults.default()).engine.root
            val embedder = Embedders.create(ec, root)
            val dim = embedder.dim
            val t0 = System.nanoTime()
            if (embedder.isActive) embedder.embedQuery("svod embedder probe")
            val ms = (System.nanoTime() - t0) / 1_000_000
            (embedder as? AutoCloseable)?.let { runCatching { it.close() } }
            EmbedderControl.ProbeResult(ok = true, dimension = dim, latencyMs = ms, error = null)
        } catch (e: EmbedderControl.InvalidSpec) {
            EmbedderControl.ProbeResult(ok = false, dimension = null, latencyMs = null, error = e.message)
        } catch (e: Throwable) {
            EmbedderControl.ProbeResult(ok = false, dimension = null, latencyMs = null, error = (e.cause ?: e).message)
        }
    }

    private fun merge(current: SvodConfig.EmbedderSettings, spec: EmbedderControl.EmbedderSpec): SvodConfig.EmbedderSettings {
        if (SvodConfig.PROVIDERS.none { it.equals(spec.provider, ignoreCase = true) }) {
            throw EmbedderControl.InvalidSpec("provider must be one of ${SvodConfig.PROVIDERS}, was '${spec.provider}'")
        }
        spec.apiKeyRef?.let { ref ->
            if (ref.isNotBlank() && !isSecretRef(ref)) {
                throw EmbedderControl.InvalidSpec("apiKeyRef must be a Secrets reference (env:/file:/keychain:), never a raw key")
            }
        }
        val isOnnx = spec.provider.lowercase() in listOf("onnx-local", "local-onnx")
        return current.copy(
            provider = spec.provider,
            onnxModelId = if (isOnnx && spec.model != null) spec.model else current.onnxModelId,
            endpoint = spec.endpoint ?: current.endpoint,
            model = if (!isOnnx) (spec.model ?: current.model) else current.model,
            apiKeyRef = spec.apiKeyRef ?: current.apiKeyRef,
            maxThreads = spec.maxThreads ?: current.maxThreads,
        )
    }

    private fun isSecretRef(ref: String) =
        ref.startsWith("env:") || ref.startsWith("file:") || ref.startsWith("keychain:")
}
