package dev.svod.engine.lifecycle

import dev.svod.engine.api.VaultRouter
import dev.svod.engine.api.VaultView

/**
 * The registry of open vaults and the App API's router into them. Opens every configured vault at
 * start (each acquiring its own exclusive lock), closes them in reverse on shutdown. Resolution
 * of a null id yields the default vault; an unknown id yields null (a 404 at the API).
 */
class VaultManager(
    private val byId: Map<String, VaultContext>,
    private val defaultId: String,
) : VaultRouter, AutoCloseable {

    fun context(id: String?): VaultContext? = byId[id ?: defaultId]
    fun default(): VaultContext = byId.getValue(defaultId)
    fun contexts(): List<VaultContext> = byId.values.toList()

    override fun ids(): List<String> = byId.keys.toList()
    override fun defaultId(): String = defaultId
    override fun resolve(id: String?): VaultView? = byId[id ?: defaultId]
    override fun all(): List<VaultView> = byId.values.toList()
    // syncNow is driven by the node (it resolves each vault's remote from the backup/sync config and
    // records the success markers); the router keeps the VaultRouter default no-op.

    override fun close() {
        // reverse open order, best-effort: one vault's failure must not strand the others
        for (vc in byId.values.toList().asReversed()) runCatching { vc.close() }
    }

    companion object {
        /** Open all resolved vaults from config. On any failure, close those already opened. */
        fun open(config: SvodConfig, scope: kotlinx.coroutines.CoroutineScope, eventBus: dev.svod.engine.events.EventBus, hostId: String): VaultManager {
            val opened = LinkedHashMap<String, VaultContext>()
            try {
                for (vs in config.resolvedVaults()) {
                    opened[vs.id] = VaultContext.open(vs, config, scope, eventBus, hostId)
                }
                return VaultManager(opened, config.defaultVaultId())
            } catch (t: Throwable) {
                for (vc in opened.values.toList().asReversed()) runCatching { vc.close() }
                throw t
            }
        }
    }
}
