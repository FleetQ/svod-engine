package dev.svod.engine.api

/**
 * Runtime control of the active embedder, injected into [AppApiServer]. Null ⇒ the embedder-control
 * endpoints (PUT /embedder, POST /embedder/test) return 501 (the macOS app feature-detects via the
 * App API version). Implemented by the lifecycle layer, which owns config + vault wiring.
 *
 * The embedder is a global engine setting (one provider for all vaults), so [apply] re-embeds every
 * vault; [vaultId] only selects which vault's dimension the returned descriptor reports.
 */
interface EmbedderControl {
    /** The currently active embedder for the engine, with [vaultId]'s indexed dimension. */
    fun descriptor(vaultId: String): EmbedderDescriptor

    /** Build + swap the embedder (background re-embed) and persist it to config. */
    fun apply(vaultId: String, spec: EmbedderSpec): EmbedderDescriptor

    /** Probe a spec WITHOUT persisting: build it, embed a probe string, report dimension + latency. */
    fun test(vaultId: String, spec: EmbedderSpec): ProbeResult

    /**
     * Enumerate the models the spec's provider can serve (for the UI's model picker). Endpoint/apiKeyRef
     * fall back to the configured embedder when omitted. A provider that can't be enumerated (endpoint
     * down, no key) yields an EMPTY list, never an error — the UI treats empty as "manual entry". A raw
     * API key (not a Secrets ref) still throws [InvalidSpec] ⇒ 422.
     */
    fun models(vaultId: String, spec: EmbedderSpec): ModelsResult

    data class EmbedderDescriptor(val provider: String, val model: String, val endpoint: String?, val dimension: Int)
    data class EmbedderSpec(val provider: String, val model: String?, val endpoint: String?, val apiKeyRef: String?, val maxThreads: Int?)
    data class ProbeResult(val ok: Boolean, val dimension: Int?, val latencyMs: Long?, val error: String?)
    data class ModelOption(val id: String, val dimension: Int?)
    data class ModelsResult(val provider: String, val models: List<ModelOption>)

    /** Thrown by [apply] for an invalid request (unknown provider, raw API key) ⇒ 422. */
    class InvalidSpec(message: String) : IllegalArgumentException(message)
}
