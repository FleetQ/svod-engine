package dev.svod.engine.lifecycle

import dev.svod.engine.api.VaultRouter
import dev.svod.engine.api.VaultView

/**
 * The registry of open vaults and the App API's router into them. Opens every configured vault at
 * start (each acquiring its own exclusive lock), closes them in reverse on shutdown. Resolution
 * of a null id yields the default vault; an unknown id yields null (a 404 at the API).
 */
class VaultManager(
    initial: Map<String, VaultContext>,
    private val defaultId: String,
) : VaultRouter, AutoCloseable {

    // Read by every request, mutated only by a rare runtime register(): a volatile snapshot swapped
    // under @Synchronized keeps reads lock-free and preserves listing order (a fresh LinkedHashMap).
    @Volatile
    private var byId: Map<String, VaultContext> = LinkedHashMap(initial)

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    /**
     * Notified when a vault is hot-added/removed at runtime. Everything that keeps its OWN per-vault
     * state — backup bindings, the MCP tool sets, source watching — must subscribe: a registry built
     * once from [contexts] at start goes stale the moment a vault is created via the API, and the
     * miss is silent (the vault lists fine in GET /vaults but has no backup binding and no tools).
     */
    interface Listener {
        fun onVaultRegistered(vc: VaultContext)
        fun onVaultUnregistered(id: String)
    }

    fun addListener(listener: Listener) { listeners.add(listener) }

    fun context(id: String?): VaultContext? = byId[id ?: defaultId]
    fun default(): VaultContext = byId.getValue(defaultId)
    fun contexts(): List<VaultContext> = byId.values.toList()

    /** Hot-add a freshly opened vault (POST /api/v1/vaults). The caller has verified its id is new. */
    fun register(vc: VaultContext) {
        synchronized(this) {
            require(vc.id !in byId) { "vault already registered: ${vc.id}" }
            byId = LinkedHashMap(byId).apply { put(vc.id, vc) }
        }
        // Outside the lock: a listener may call back into the manager (e.g. to read contexts()).
        for (l in listeners) l.onVaultRegistered(vc)
    }

    /**
     * Hot-remove a vault (DELETE /api/v1/vaults/{id}); returns the detached context so the caller can
     * close() it (releasing its lock + handles), or null if no such vault is registered. After this
     * returns, GET /vaults no longer lists it and `?vault=<id>` resolves to null (a 404).
     */
    fun unregister(id: String): VaultContext? {
        val vc = synchronized(this) {
            val found = byId[id] ?: return null
            byId = LinkedHashMap(byId).apply { remove(id) }
            found
        }
        for (l in listeners) l.onVaultUnregistered(id)
        return vc
    }

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
