package dev.svod.engine.sources

import dev.svod.engine.core.Coalescer
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Owns the per-source [SourceWatcher]s across every vault and keeps the running set reconciled with
 * each vault's registrations. A source is watched iff `autoSync` is on AND its path currently exists.
 *
 * Lifecycle: [start] watches everything at boot; [reconcile] is called by the API after a
 * register/PATCH/remove so a toggle takes effect with no restart. A supervisor tick re-reconciles
 * every [superviseMs] to (re)start a watcher whose path has reappeared and to drop one whose path
 * vanished or whose underlying watch died. [isWatching] backs the live `watching` flag in the API.
 */
class SourceWatchManager(
    private val scope: CoroutineScope,
    private val eventBus: EventBus,
    vaults: List<Vault>,
    private val debounceMs: Long = 250,
    private val superviseMs: Long = 30_000,
    /** One write-back pass over a vault's writeBack sources. */
    private val writeBackSync: suspend (Vault) -> Unit = { syncWriteBackSources(it) },
) {
    /** A vault the manager can watch sources for: its id, engine (for sync), and root (for the store). */
    data class Vault(val id: String, val engine: SvodEngine, val root: java.nio.file.Path)

    private val log = LoggerFactory.getLogger(SourceWatchManager::class.java)
    // Mutable: a vault created at runtime must be watchable without a restart (a stale map means its
    // autoSync sources are registered but never actually watched).
    private val byId = java.util.concurrent.ConcurrentHashMap<String, Vault>(vaults.associateBy { it.id })
    private val watchers = HashMap<String, SourceWatcher>() // key = "vaultId\u0000sourceId"
    private val lock = Any()
    private var supervisor: Job? = null
    private var commitListener: Job? = null
    // vaultId → debounced write-back; a commit during a running write-back earns one trailing run
    // instead of cancelling it.
    private val writeBack = Coalescer(scope, debounceMs * 3) { vaultId, _ -> writeBackNow(vaultId) }

    private fun key(vaultId: String, sourceId: String) = "$vaultId\u0000$sourceId"

    fun start() {
        reconcileAll()
        supervisor = scope.launch {
            while (isActive) {
                delay(superviseMs)
                runCatching { reconcileAll() }.onFailure { log.warn("source-watch supervision failed", it) }
            }
        }
        // Write-back trigger: a vault commit (UI save, MCP agent write) re-syncs that vault's
        // writeBack sources shortly after, so the edit lands in the external file within ~a second
        // instead of waiting for the next manual/scheduled sync. Events without a vault tag (some
        // publishers omit it) fan out to every vault that has writeBack sources — the sync itself
        // is cheap for an unchanged source. Our own sync commits are skipped to avoid loops.
        commitListener = scope.launch {
            eventBus.events.collect { ev ->
                if (ev.type != EventTypes.COMMIT_CREATED) return@collect
                val author = (ev.data["author"] as? JsonPrimitive)?.contentOrNull
                if (author == "external-source-sync") return@collect
                val vault = (ev.data["vault"] as? JsonPrimitive)?.contentOrNull
                scheduleWriteBack(vault)
            }
        }
    }

    /** Debounced per-vault write-back sync (coalesces bursts of commits). */
    private fun scheduleWriteBack(vaultId: String?) {
        val targets = if (vaultId != null) listOfNotNull(byId[vaultId]) else byId.values.toList()
        for (v in targets) writeBack.changed(v.id)
    }

    private suspend fun writeBackNow(vaultId: String) {
        byId[vaultId]?.let { writeBackSync(it) }
    }

    fun isWatching(vaultId: String, sourceId: String): Boolean =
        synchronized(lock) { watchers[key(vaultId, sourceId)]?.isAlive == true }

    private fun reconcileAll() { for (id in byId.keys) reconcile(id) }

    /** Start watching a vault hot-added at runtime (its sources are watched from now on). */
    fun addVault(v: Vault) {
        byId[v.id] = v
        reconcile(v.id)
    }

    /** Stop watching a deleted vault and tear down every watcher it still owns. */
    fun removeVault(vaultId: String) {
        byId.remove(vaultId)
        writeBack.cancel(vaultId)
        synchronized(lock) {
            val it = watchers.entries.iterator()
            while (it.hasNext()) {
                val (k, w) = it.next()
                if (!k.startsWith(key(vaultId, ""))) continue
                runCatching { w.close() }
                it.remove()
            }
        }
    }

    /** Make the running watchers for [vaultId] match its registrations (called by the API on change). */
    fun reconcile(vaultId: String) {
        val v = byId[vaultId] ?: return
        val store = ExternalSourceStore(v.root)
        val desired = store.list().filter { it.autoSync && pathExists(it) }
        val desiredIds = desired.map { it.id }.toSet()
        synchronized(lock) {
            // Stop watchers no longer wanted (autoSync off / removed / path gone). A watcher is NOT
            // torn down for a transient `!isAlive` — it self-heals its own watch internally, and
            // closing it here would cancel an in-flight sync ("Job was cancelled").
            val it = watchers.entries.iterator()
            while (it.hasNext()) {
                val (k, w) = it.next()
                if (!k.startsWith("$vaultId\u0000")) continue
                val sourceId = k.substringAfter('\u0000')
                if (sourceId !in desiredIds) { runCatching { w.close() }; it.remove() }
            }
            // Start watchers that should run but aren't (new autoSync source, or one whose path returned).
            for (s in desired) {
                val k = key(vaultId, s.id)
                if (watchers[k] == null) {
                    val w = SourceWatcher(vaultId, v.engine, store, eventBus, s.id, s.path, debounceMs).start()
                    if (w != null) { watchers[k] = w; log.info("watching source '{}' ({}) for auto-sync", s.id, s.path) }
                }
            }
        }
    }

    private fun pathExists(s: ExternalSource): Boolean =
        runCatching { Files.exists(Paths.get(s.path)) }.getOrDefault(false)

    fun stop() {
        supervisor?.cancel(); supervisor = null
        commitListener?.cancel(); commitListener = null
        synchronized(lock) {
            writeBack.stop()
            watchers.values.forEach { runCatching { it.close() } }
            watchers.clear()
        }
    }
}

private suspend fun syncWriteBackSources(v: SourceWatchManager.Vault) {
    val log = LoggerFactory.getLogger(SourceWatchManager::class.java)
    val store = ExternalSourceStore(v.root)
    val sources = store.list().filter { it.writeBack }
    if (sources.isEmpty()) return
    val sync = SourceSync(v.engine, store)
    for (s in sources) {
        runCatching { sync.sync(s) }
            .onSuccess { r -> if (r.pushed.isNotEmpty()) log.info("write-back '{}': pushed {}", s.id, r.pushed) }
            .onFailure { log.warn("write-back sync of '{}' failed", s.id, it) }
    }
}
