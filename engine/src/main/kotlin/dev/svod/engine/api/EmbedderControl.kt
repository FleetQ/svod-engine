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

    data class EmbedderDescriptor(val provider: String, val model: String, val endpoint: String?, val dimension: Int)
    data class EmbedderSpec(val provider: String, val model: String?, val endpoint: String?, val apiKeyRef: String?, val maxThreads: Int?)
    data class ProbeResult(val ok: Boolean, val dimension: Int?, val latencyMs: Long?, val error: String?)

    /** Thrown by [apply] for an invalid request (unknown provider, raw API key) ⇒ 422. */
    class InvalidSpec(message: String) : IllegalArgumentException(message)
}
