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
    override val graph: dev.svod.engine.graphrag.GraphService,
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
        // Before the index: a running graph build reads from it.
        runCatching { graph.close() }
        runCatching { index.close() }
        runCatching { engine.close() }
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(VaultContext::class.java)

        /** Runs [body], logging how long it took. Boot phases only — this must stay off hot paths. */
        private inline fun <T> phase(vault: String, name: String, body: () -> T): T {
            val t0 = System.currentTimeMillis()
            val result = body()
            log.info("vault $vault: $name took ${System.currentTimeMillis() - t0} ms")
            return result
        }

        fun open(vs: SvodConfig.VaultSettings, config: SvodConfig, scope: CoroutineScope, eventBus: EventBus, hostId: String): VaultContext {
            val vault = Paths.get(vs.path)
            // Single-instance per vault: SvodEngine.open acquires the exclusive vault lock. Commits
            // record committer = this machine's host id (author stays the agent/UI) for sync provenance.
            val committer = dev.svod.engine.core.Author(hostId, "$hostId@svod.local")
            // Boot phases are timed because "cold start is 25 s - 7.5 min" was, until now, a number
            // with no breakdown behind it — and an optimisation without a breakdown is a guess.
            val engine = phase(vs.id, "engine open") {
                SvodEngine.open(vault, scope, dev.svod.engine.security.SecretScanner(config.secretScanning), committer)
            }
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
                phase(vs.id, "index start") { index.start() }
                engine.onCommit { index.onCommit(it) }

                val conflicts = ConflictStore()
                // The sync engine is always assembled (cheap — just handles); whether a vault actually
                // reconciles is decided at runtime by its sync config (driven by the SyncScheduler /
                // POST /sync/now), so toggling sync needs no restart and no vault re-open.
                val syncGit = SyncGit(vault)
                val syncEngine = SyncEngine(engine, syncGit, conflicts, eventBus, vs.id, hostId)

                // The suspect: methvin's DirectoryWatcher hashes the whole tree when it is built.
                val watcher = phase(vs.id, "file watcher start") { FileWatcher(vault, engine, index, eventBus).start() }

                // Derived thematic graph. Disabled by default; start() is a no-op unless enabled, and
                // a build never blocks startup or touches the Lucene index.
                val gc = config.toGraphConfig()
                val graph = phase(vs.id, "graph start") {
                    dev.svod.engine.graphrag.GraphService(
                        vault.resolve(".svod").resolve("graph"),
                        engine,
                        index,
                        dev.svod.engine.graphrag.SummaryLlms.create(gc.summary),
                        gc,
                    ).start()
                }
                // Chain the graph onto the hook the index ALREADY fires after catching up to a
                // commit, rather than adding a second listener: the graph's incremental attachment
                // is a function of what the index just indexed, so it must run after that, not after
                // the commit. The original publisher is preserved — it was wired before index.start()
                // on purpose, so the earliest progress ticks are not lost.
                val publishSynced = index.onSynced
                index.onSynced = { head ->
                    publishSynced?.invoke(head)
                    graph.onIndexSynced()
                }

                return VaultContext(vs.id, vs.name ?: vs.id, engine, index, conflicts, syncEngine, syncGit, watcher, graph)
            } catch (t: Throwable) {
                engine.close()
                throw t
            }
        }
    }
}
