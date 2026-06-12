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

interface VaultRouter {
    fun ids(): List<String>
    fun defaultId(): String
    /** Resolve a vault id; null id ⇒ the default vault; an unknown id ⇒ null (→ 404). */
    fun resolve(id: String?): VaultView?
    fun all(): List<VaultView>
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
