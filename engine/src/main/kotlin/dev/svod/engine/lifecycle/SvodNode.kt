package dev.svod.engine.lifecycle

import dev.svod.engine.api.AppApiServer
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.mcp.AgentRegistry
import dev.svod.engine.mcp.AuditLog
import dev.svod.engine.mcp.RateLimiter
import dev.svod.engine.mcp.SvodTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A running Svod engine: the assembled, lifecycle-managed node. [start] validates config, opens
 * every configured vault (each acquiring its own exclusive lock), brings up the MCP endpoint, the
 * App API (routing to vaults), then flips readiness on.
 *
 * [shutdown] is graceful and ordered: stop accepting requests, then close every vault — which
 * stops its watcher/sync, closes its index, and drains its write-actor queue before releasing the
 * lock. Nothing in flight is lost; a committed write is always recoverable.
 */
class SvodNode private constructor(
    val config: SvodConfig,
    private val vaults: VaultManager,
    val eventBus: EventBus,
    private val mcp: dev.svod.engine.mcp.SvodMcpServer.Running,
    private val api: AppApiServer.Running,
    val backup: dev.svod.engine.sync.BackupService,
    private val ready: AtomicBoolean,
    private val ownsScope: CoroutineScope,
) : AutoCloseable {

    val appApiPort: Int get() = api.port
    val mcpPort: Int get() = mcp.port
    fun isReady(): Boolean = ready.get()

    /** The default vault's engine (back-compat convenience for single-vault callers/tests). */
    val engine: SvodEngine get() = vaults.default().engine

    /** Trigger one reconcile with peers across every vault (no-op where sync isn't configured). */
    suspend fun sync() {
        for (vc in vaults.contexts()) vc.sync()
    }

    @Volatile
    private var stopped = false

    fun shutdown() {
        if (stopped) return
        stopped = true
        ready.set(false)
        // 1. stop accepting new work
        runCatching { api.stop() }
        runCatching { mcp.stop() }
        // 2. close every vault: watcher + peers, then index, then the engine (drains the queue).
        runCatching { vaults.close() }
    }

    override fun close() = shutdown()

    companion object {
        fun start(config: SvodConfig, scope: CoroutineScope? = null): SvodNode {
            val errors = config.validate()
            require(errors.isEmpty()) { "invalid config:\n - " + errors.joinToString("\n - ") }

            val workScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val eventBus = EventBus()
            val vaults = VaultManager.open(config, workScope, eventBus)
            try {
                // Per-agent vault scoping: each vault has its own tool set (own engine/index/audit).
                // A call selects its target vault (default = the agent's first grant); the MCP layer
                // enforces the agent's grant, so a multi-grant agent can reach every granted vault and
                // an ungranted vault is denied.
                val registry = AgentRegistry(config.toAgentSpecs())
                val rateLimiter = RateLimiter.default()
                val defaultId = vaults.defaultId()
                val toolsByVault = vaults.contexts().associate { vc ->
                    val audit = AuditLog(vc.engine.root.resolve(".svod").resolve("audit").resolve("audit.log"))
                    vc.id to SvodTools(vc.engine, vc.index, audit, rateLimiter, eventBus)
                }

                val ready = AtomicBoolean(false)
                // Per-vault backup: each vault gets its own remote + persistence (a runtime PUT is
                // saved to that vault's .svod/backup.json and survives a restart, taking precedence
                // over the startup config; falls back to a per-vault or global config remote).
                val backup = dev.svod.engine.sync.BackupService(
                    vaults.contexts().map { vc ->
                        val store = BackupConfigStore(vc.engine.root)
                        val effective = store.load()?.let { SvodConfig.BackupSettings(it.remote, it.enabled) } ?: config.backupFor(vc.id)
                        dev.svod.engine.sync.BackupService.Binding(vc.id, vc.engine.root, effective, store)
                    },
                )
                val api = AppApiServer(
                    vaults = vaults,
                    eventBus = eventBus,
                    config = AppApiServer.Config(
                        host = config.host,
                        embedderProvider = config.embedder.provider,
                        webViewerPath = config.webViewerPath,
                    ),
                    readiness = { ready.get() },
                    backup = backup,
                    syncConfig = { vc ->
                        val peers = config.syncRemotesFor(vc.id).map { dev.svod.engine.lifecycle.SvodConfig.redactRemote(it) }
                        dev.svod.engine.api.SyncConfigDto(
                            role = vc.syncStatus()?.role ?: "standalone",
                            hostId = config.hostIdFor(vc.id),
                            syncConfigured = peers.isNotEmpty(),
                            syncIntervalSeconds = config.syncIntervalSecondsFor(vc.id),
                            peers = peers,
                            backup = backup.configOf(vc.id)?.let { dev.svod.engine.api.BackupConfigDto(dev.svod.engine.lifecycle.SvodConfig.redactRemote(it.remote), it.enabled) },
                        )
                    },
                ).start(config.appApiPort)

                val mcpServer = dev.svod.engine.mcp.SvodMcpServer({ vid -> toolsByVault[vid] }, defaultId, registry, host = config.host)
                val mcpTls = config.mcpTls?.let { t ->
                    val ks = dev.svod.engine.security.Keystores.load(
                        java.nio.file.Paths.get(t.keystorePath),
                        dev.svod.engine.security.Secrets.resolve(t.keystorePassword).toCharArray(),
                    )
                    dev.svod.engine.mcp.SvodMcpServer.Tls(ks, t.keyAlias,
                        dev.svod.engine.security.Secrets.resolve(t.keystorePassword).toCharArray(),
                        dev.svod.engine.security.Secrets.resolve(t.keyPassword).toCharArray())
                }
                val mcp = mcpServer.start(config.mcpPort, mcpTls)

                ready.set(true)
                eventBus.publish(dev.svod.engine.events.EventTypes.ENGINE_STATUS) { put("status", "ready") }
                return SvodNode(config, vaults, eventBus, mcp, api, backup, ready, workScope)
            } catch (t: Throwable) {
                vaults.close()
                throw t
            }
        }
    }
}
