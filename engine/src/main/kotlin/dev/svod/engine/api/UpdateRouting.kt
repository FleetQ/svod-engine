package dev.svod.engine.api

interface UpdateAdmin {
    suspend fun check(): UpdateCheckDto
    suspend fun apply(): UpdateApplyDto

    /** Thrown when the runtime does not support self-update (e.g. no script configured) → 501. */
    class NotSupported(msg: String) : Exception(msg)

    /** Thrown when an update exists but cannot be applied right now (e.g. none available) → 409. */
    class NotApplicable(msg: String) : Exception(msg)
}
