package dev.svod.engine.api

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.index.IndexService
import dev.svod.engine.sync.ConflictStore

/**
 * The App API's view of one vault and the router that resolves `?vault=` to it. This keeps the
 * server decoupled from the lifecycle layer: production wires a multi-vault manager, while tests
 * and simple embeds use the single-vault convenience constructor on [AppApiServer].
 */
interface VaultView {
    val id: String
    val name: String
    val engine: SvodEngine
    val index: IndexService
    val conflicts: ConflictStore?
    fun syncStatus(): SyncStatusDto?
}

/**
 * Runtime creation of a brand-new vault (POST /api/v1/vaults). Production wires the lifecycle
 * implementation; when it is null the endpoint returns 501. Failures map to an HTTP status via the
 * typed exceptions below so the server stays decoupled from the lifecycle layer.
 */
interface VaultCreator {
    /** Bad id (pattern) or an unusable path shape ⇒ 400. */
    class InvalidRequest(message: String) : Exception(message)
    /** Duplicate vault id, or the target directory exists and is non-empty ⇒ 409. */
    class Conflict(message: String) : Exception(message)
    /** The target path can't be created or isn't writable ⇒ 422. */
    class NotWritable(message: String) : Exception(message)

    /** Create the vault (dir + git + seed commit), persist it, hot-add it; return its view for the 201 body. */
    suspend fun create(req: CreateVaultRequest): VaultView
}

interface VaultRouter {
    fun ids(): List<String>
    fun defaultId(): String
    /** Resolve a vault id; null id ⇒ the default vault; an unknown id ⇒ null (→ 404). */
    fun resolve(id: String?): VaultView?
    fun all(): List<VaultView>
    /** Trigger one reconcile with peers for [vaultId] (no-op where sync isn't configured). */
    suspend fun syncNow(vaultId: String) {}
}

/** A trivial single-vault router (back-compat: the App API used to own exactly one engine). */
internal class SingleVaultRouter(
    engine: SvodEngine,
    index: IndexService,
    conflicts: ConflictStore?,
    private val sync: () -> SyncStatusDto?,
    private val vaultId: String = "default",
) : VaultRouter {
    private val view = object : VaultView {
        override val id = vaultId
        override val name = vaultId
        override val engine = engine
        override val index = index
        override val conflicts = conflicts
        override fun syncStatus() = sync()
    }
    override fun ids() = listOf(vaultId)
    override fun defaultId() = vaultId
    override fun resolve(id: String?) = if (id == null || id == vaultId) view else null
    override fun all() = listOf(view)
}
