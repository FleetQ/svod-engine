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

    /**
     * The derived thematic graph, when this vault has one. Defaulted so existing implementations
     * (tests, fixtures) need no change and a vault without the feature simply reports "not built".
     */
    val graph: dev.svod.engine.graphrag.GraphService? get() = null

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

/**
 * Runtime removal of a vault (DELETE /api/v1/vaults/{id}). The engine does the LOGICAL removal —
 * release the vault's lock + git/index handles, unregister it from the running engine, and drop it
 * from the persistent config — and, only when asked, also hard-deletes the directory. When it does
 * not delete the files it returns the directory path so the caller can dispose of it (e.g. move it
 * to the OS Trash). Null wiring ⇒ the endpoint returns 501.
 */
interface VaultRemover {
    /** No vault with this id ⇒ 404. */
    class UnknownVault(message: String) : Exception(message)
    /** Refusing to delete the default vault, or the last remaining vault ⇒ 409. */
    class Conflict(message: String) : Exception(message)

    /** Release locks/handles, unregister, drop from config, optionally hard-delete the dir; return the result. */
    suspend fun delete(id: String, deleteFiles: Boolean): DeleteVaultResultDto
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
