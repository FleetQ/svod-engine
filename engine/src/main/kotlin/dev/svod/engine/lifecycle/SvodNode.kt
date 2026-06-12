package dev.svod.engine.lifecycle

import dev.svod.engine.api.AppApiServer
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.index.Embedders
import dev.svod.engine.index.IndexService
import dev.svod.engine.mcp.AgentRegistry
import dev.svod.engine.mcp.AuditLog
import dev.svod.engine.mcp.RateLimiter
import dev.svod.engine.mcp.SvodTools
import dev.svod.engine.watch.FileWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A running Svod engine: the assembled, lifecycle-managed node. [start] validates config,
 * acquires the single-instance lock (via the engine's vault lock), brings up the index, the
 * MCP endpoint, the App API, and the file watcher, then flips readiness on.
 *
 * [shutdown] is graceful and ordered: stop accepting requests, stop watching, close the
 * index (Lucene), then close the engine — which drains the write-actor queue and releases
 * the lock. Nothing in flight is lost; a committed write is always recoverable.
 */
class SvodNode private constructor(
    val config: SvodConfig,
    val engine: SvodEngine,
    private val index: IndexService,
    val eventBus: EventBus,
    private val mcp: dev.svod.engine.mcp.SvodMcpServer.Running,
    private val api: AppApiServer.Running,
    private val watcher: FileWatcher,
    private val ready: AtomicBoolean,
    private val ownsScope: CoroutineScope,
) : AutoCloseable {

    val appApiPort: Int get() = api.port
    val mcpPort: Int get() = mcp.port
    fun isReady(): Boolean = ready.get()

    @Volatile
    private var stopped = false

    fun shutdown() {
        if (stopped) return
        stopped = true
        ready.set(false)
        // 1. stop accepting new work
        runCatching { api.stop() }
        runCatching { mcp.stop() }
        // 2. stop watching the filesystem
        runCatching { watcher.close() }
        // 3. close derived state, then the source of truth (drains the write-actor queue)
        runCatching { index.close() }
        runCatching { engine.close() }
    }

    override fun close() = shutdown()

    companion object {
        fun start(config: SvodConfig, scope: CoroutineScope? = null): SvodNode {
            val errors = config.validate()
            require(errors.isEmpty()) { "invalid config:\n - " + errors.joinToString("\n - ") }

            val workScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val vault = config.vault()

            // Single-instance: SvodEngine.open acquires the exclusive vault lock and fails if
            // another instance already holds it.
            val engine = SvodEngine.open(vault, workScope)
            try {
                val embedder = Embedders.create(config.toEmbedderConfig(), vault)
                val index = IndexService(vault, vault.resolve(".svod").resolve("index"), embedder).start()
                engine.onCommit { index.onCommit(it) }

                val eventBus = EventBus()
                val audit = AuditLog(vault.resolve(".svod").resolve("audit").resolve("audit.log"))
                val registry = AgentRegistry(config.toAgentSpecs())
                val tools = SvodTools(engine, index, audit, RateLimiter.default(), eventBus)

                val ready = AtomicBoolean(false)
                val api = AppApiServer(
                    svod = engine,
                    index = index,
                    eventBus = eventBus,
                    config = AppApiServer.Config(
                        host = config.host,
                        embedderProvider = config.embedder.provider,
                        webViewerPath = config.webViewerPath,
                    ),
                    readiness = { ready.get() },
                ).start(config.appApiPort)
                val mcp = dev.svod.engine.mcp.SvodMcpServer(tools, registry, host = config.host).start(config.mcpPort)
                val watcher = FileWatcher(vault, engine, index, eventBus).start()

                ready.set(true)
                eventBus.publish(dev.svod.engine.events.EventTypes.ENGINE_STATUS) { put("status", "ready") }
                return SvodNode(config, engine, index, eventBus, mcp, api, watcher, ready, workScope)
            } catch (t: Throwable) {
                engine.close()
                throw t
            }
        }
    }
}
