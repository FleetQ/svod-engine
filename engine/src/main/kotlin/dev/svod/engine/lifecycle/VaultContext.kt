package dev.svod.engine.lifecycle

import dev.svod.engine.api.SyncStatusDto
import dev.svod.engine.api.VaultView
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.index.Embedders
import dev.svod.engine.index.IndexService
import dev.svod.engine.sync.ConflictStore
import dev.svod.engine.sync.SyncEngine
import dev.svod.engine.sync.SyncGit
import dev.svod.engine.watch.FileWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import java.nio.file.Paths

/**
 * One vault, fully assembled: its own engine (exclusive lock), index, conflict store, file
 * watcher, and optional sync. This is the unit the [VaultManager] manages and the App API routes
 * to. Each vault keeps the engine's integrity guarantees independently — a stall in one vault's
 * writer never blocks another.
 */
class VaultContext private constructor(
    override val id: String,
    override val name: String,
    override val engine: SvodEngine,
    override val index: IndexService,
    private val conflictStore: ConflictStore,
    val syncEngine: SyncEngine?,
    private val syncGit: SyncGit?,
    private val watcher: FileWatcher,
    private val syncJob: Job?,
) : VaultView, AutoCloseable {

    override val conflicts: ConflictStore get() = conflictStore

    override fun syncStatus(): SyncStatusDto? = syncEngine?.let { se ->
        val r = se.lastResult
        SyncStatusDto(role = se.role, lastHead = r?.head, conflicts = r?.conflicts ?: 0)
    }

    suspend fun sync() { syncEngine?.sync() }

    /** Graceful, ordered close: stop watching + peers, close the index, then drain the engine. */
    override fun close() {
        runCatching { syncJob?.cancel() }
        runCatching { watcher.close() }
        runCatching { syncGit?.close() }
        runCatching { index.close() }
        runCatching { engine.close() }
    }

    companion object {
        fun open(vs: SvodConfig.VaultSettings, config: SvodConfig, scope: CoroutineScope, eventBus: EventBus): VaultContext {
            val vault = Paths.get(vs.path)
            // Single-instance per vault: SvodEngine.open acquires the exclusive vault lock.
            val engine = SvodEngine.open(vault, scope, dev.svod.engine.security.SecretScanner(config.secretScanning))
            try {
                val embedder = Embedders.create(config.toEmbedderConfig(), vault)
                val index = IndexService(vault, vault.resolve(".svod").resolve("index"), embedder).start()
                engine.onCommit { index.onCommit(it) }
                index.onSynced = { head -> eventBus.publish(EventTypes.INDEX_UPDATED) { put("vault", vs.id); put("head", head) } }

                val conflicts = ConflictStore()
                val syncGit: SyncGit?
                val syncEngine: SyncEngine?
                if (vs.syncRemotes.isNotEmpty()) {
                    syncGit = SyncGit(vault)
                    syncEngine = SyncEngine(engine, syncGit, conflicts, eventBus, vs.hostId, vs.mergeAuthority, vs.syncRemotes.first())
                } else {
                    syncGit = null; syncEngine = null
                }

                val watcher = FileWatcher(vault, engine, index, eventBus).start()

                val syncJob: Job? = if (syncEngine != null && vs.syncIntervalSeconds > 0) {
                    scope.launch {
                        while (isActive) {
                            delay(vs.syncIntervalSeconds * 1000L)
                            runCatching { syncEngine.sync() }.onFailure { System.err.println("sync failed [${vs.id}]: $it") }
                        }
                    }
                } else null

                return VaultContext(vs.id, vs.name ?: vs.id, engine, index, conflicts, syncEngine, syncGit, watcher, syncJob)
            } catch (t: Throwable) {
                engine.close()
                throw t
            }
        }
    }
}
