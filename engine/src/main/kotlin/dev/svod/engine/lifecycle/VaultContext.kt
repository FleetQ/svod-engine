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
    val syncEngine: SyncEngine,
    private val syncGit: SyncGit,
    private val watcher: FileWatcher,
) : VaultView, AutoCloseable {

    override val conflicts: ConflictStore get() = conflictStore

    // A sync result exists only once a synced vault has run a cycle; a solo vault (never synced)
    // reports no sync status. The authoritative role for the UI comes from GET /sync/config.
    override fun syncStatus(): SyncStatusDto? = syncEngine.lastResult?.let { r ->
        SyncStatusDto(role = "synced", lastHead = r.head, conflicts = r.conflicts, syncStatus = r.status.name, lastSyncedAt = r.lastSyncedAt)
    }

    /** Run one reconcile cycle against [remote] (caller resolves it from the vault's sync config). */
    suspend fun sync(remote: String): SyncEngine.Result = syncEngine.sync(remote)

    /** Graceful, ordered close: stop watching, close the index, then drain the engine. */
    override fun close() {
        runCatching { watcher.close() }
        runCatching { syncGit.close() }
        runCatching { index.close() }
        runCatching { engine.close() }
    }

    companion object {
        fun open(vs: SvodConfig.VaultSettings, config: SvodConfig, scope: CoroutineScope, eventBus: EventBus, hostId: String): VaultContext {
            val vault = Paths.get(vs.path)
            // Single-instance per vault: SvodEngine.open acquires the exclusive vault lock. Commits
            // record committer = this machine's host id (author stays the agent/UI) for sync provenance.
            val committer = dev.svod.engine.core.Author(hostId, "$hostId@svod.local")
            val engine = SvodEngine.open(vault, scope, dev.svod.engine.security.SecretScanner(config.secretScanning), committer)
            try {
                val ec = config.toEmbedderConfig()
                val embedder = Embedders.create(ec, vault)
                val rc = config.toRerankerConfig()
                val index = IndexService(
                    vault, vault.resolve(".svod").resolve("index"), embedder,
                    blockStartup = config.indexing.blockStartup,
                    maxThreads = ec.maxThreads,
                    batchSize = ec.batchSize,
                    reranker = dev.svod.engine.index.Rerankers.create(rc),
                    rerankTopK = rc.topK,
                    includeMessyInRecall = config.includeMessyInRecall,
                )
                // Wire progress BEFORE start() so the very first background-embedding ticks are seen.
                index.onSynced = { head -> eventBus.publish(EventTypes.INDEX_UPDATED) { put("vault", vs.id); put("head", head) } }
                index.onProgress = { done, total, state ->
                    eventBus.publish(EventTypes.INDEX_PROGRESS) {
                        put("vault", vs.id); put("done", done); put("total", total); put("state", state)
                    }
                }
                index.start()
                engine.onCommit { index.onCommit(it) }

                val conflicts = ConflictStore()
                // The sync engine is always assembled (cheap — just handles); whether a vault actually
                // reconciles is decided at runtime by its sync config (driven by the SyncScheduler /
                // POST /sync/now), so toggling sync needs no restart and no vault re-open.
                val syncGit = SyncGit(vault)
                val syncEngine = SyncEngine(engine, syncGit, conflicts, eventBus, vs.id, hostId)

                val watcher = FileWatcher(vault, engine, index, eventBus).start()

                return VaultContext(vs.id, vs.name ?: vs.id, engine, index, conflicts, syncEngine, syncGit, watcher)
            } catch (t: Throwable) {
                engine.close()
                throw t
            }
        }
    }
}
