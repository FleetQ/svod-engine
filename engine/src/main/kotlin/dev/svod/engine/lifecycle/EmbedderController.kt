package dev.svod.engine.lifecycle

import dev.svod.engine.api.EmbedderControl
import dev.svod.engine.index.EmbedderProvider
import dev.svod.engine.index.Embedders
import dev.svod.engine.index.ModelManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Runtime embedder control for the App API. The embedder is a global engine setting (one provider
 * for every vault), so [apply] rebuilds the embedder for ALL vaults and persists the change through
 * the shared [ConfigStore] (which writes the config file when a path is known). API keys are accepted
 * only as `Secrets` references — a raw key over the wire is rejected ([EmbedderControl.InvalidSpec] ⇒ 422).
 */
class EmbedderController(
    private val vaults: VaultManager,
    private val configStore: ConfigStore,
) : EmbedderControl {

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun descriptor(vaultId: String): EmbedderControl.EmbedderDescriptor {
        val ec = configStore.config.toEmbedderConfig()
        val dim = vaults.context(vaultId)?.index?.indexedDim() ?: 0
        return EmbedderControl.EmbedderDescriptor(ec.providerName, ec.modelName, ec.endpointOrNull, dim)
    }

    override fun apply(vaultId: String, spec: EmbedderControl.EmbedderSpec): EmbedderControl.EmbedderDescriptor {
        val merged = merge(configStore.config.embedder, spec)
        val updated = configStore.config.copy(embedder = merged)
        val errors = updated.validate()
        if (errors.isNotEmpty()) throw EmbedderControl.InvalidSpec(errors.joinToString("; "))

        val ec = updated.toEmbedderConfig()
        // Build first (probes remote endpoints) so a bad spec fails before we touch any vault.
        for (vc in vaults.contexts()) {
            val embedder = Embedders.create(ec, vc.engine.root)
            vc.index.setEmbedder(embedder)
        }
        // Persist via the shared store (preserves any vault added concurrently by VaultController).
        configStore.update { it.copy(embedder = merged) }
        return descriptor(vaultId)
    }

    override fun test(vaultId: String, spec: EmbedderControl.EmbedderSpec): EmbedderControl.ProbeResult {
        return try {
            val ec = configStore.config.copy(embedder = merge(configStore.config.embedder, spec)).toEmbedderConfig()
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

    override fun models(vaultId: String, spec: EmbedderControl.EmbedderSpec): EmbedderControl.ModelsResult {
        // merge() validates (unknown provider / raw API key ⇒ InvalidSpec ⇒ 422) and resolves the
        // effective endpoint/apiKeyRef from config when the spec omits them.
        val ec = configStore.config.copy(embedder = merge(configStore.config.embedder, spec)).toEmbedderConfig()
        val options = try {
            when (ec.provider) {
                EmbedderProvider.NONE -> emptyList()
                EmbedderProvider.ONNX_LOCAL -> ModelManager.BUNDLED.map { EmbedderControl.ModelOption(it.key, it.value) }
                EmbedderProvider.OLLAMA -> ollamaModels(ec.ollamaEndpoint)
                EmbedderProvider.OPENAI -> openaiModels(ec.openaiEndpoint, ec.openaiApiKeyRef)
            }
        } catch (_: Exception) {
            emptyList() // a provider that can't be enumerated (down / no key) ⇒ empty, never an error
        }
        return EmbedderControl.ModelsResult(ec.providerName, options)
    }

    /** Installed Ollama models via GET {endpoint}/api/tags (`models[].name`); no dimension is cheap to know. */
    private fun ollamaModels(endpoint: String): List<EmbedderControl.ModelOption> {
        val body = httpGet("$endpoint/api/tags", null) ?: return emptyList()
        val models = (json.parseToJsonElement(body) as? JsonObject)?.get("models") as? JsonArray ?: return emptyList()
        return models.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }
            .map { EmbedderControl.ModelOption(it, null) }
    }

    /** OpenAI-compatible models via GET {endpoint}/v1/models (`data[].id`). The key is a resolved Secrets ref. */
    private fun openaiModels(endpoint: String, apiKeyRef: String?): List<EmbedderControl.ModelOption> {
        val key = apiKeyRef?.takeIf { it.isNotBlank() }?.let { dev.svod.engine.security.Secrets.resolve(it) }
        val body = httpGet("$endpoint/v1/models", key) ?: return emptyList()
        val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return data.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content }
            .map { EmbedderControl.ModelOption(it, null) }
    }

    /** GET [url] (optionally with a Bearer key); returns the body on HTTP 200, else null. Never throws. */
    private fun httpGet(url: String, bearer: String?): String? = try {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .apply { if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer") }
            .GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 200) resp.body() else null
    } catch (_: Exception) {
        null
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
