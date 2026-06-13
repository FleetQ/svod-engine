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
        private val log = org.slf4j.LoggerFactory.getLogger(SvodNode::class.java)

        fun start(config: SvodConfig, scope: CoroutineScope? = null): SvodNode {
            val errors = config.validate()
            require(errors.isEmpty()) { "invalid config:\n - " + errors.joinToString("\n - ") }

            val workScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val eventBus = EventBus()
            val vaults = VaultManager.open(config, workScope, eventBus)
            try {
                // Per-agent vault scoping: each vault has its own tool set (own engine/index/audit),
                // and an agent's MCP session is bound to its granted (primary) vault. An agent
                // scoped to "work" gets only work's tools — it cannot reach another vault.
                val registry = AgentRegistry(config.toAgentSpecs())
                val rateLimiter = RateLimiter.default()
                val defaultId = vaults.defaultId()
                val toolsByVault = vaults.contexts().associate { vc ->
                    val audit = AuditLog(vc.engine.root.resolve(".svod").resolve("audit").resolve("audit.log"))
                    vc.id to SvodTools(vc.engine, vc.index, audit, rateLimiter, eventBus)
                }
                val toolsFor: (dev.svod.engine.mcp.AgentIdentity) -> SvodTools =
                    { agent -> toolsByVault[agent.primaryVault(defaultId)] ?: toolsByVault.getValue(defaultId) }
                // A multi-grant agent currently binds to its FIRST vault only (per-request vault
                // selection in MCP is a future increment, ADR-0011 §6). Surface this loudly so it is
                // never a silent surprise rather than leaving extra grants quietly unreachable.
                for (a in config.agents) if (a.vaults.size > 1) {
                    log.warn(
                        "agent '{}' is granted {} vaults {}; its MCP session binds to the first ('{}') — additional grants are not yet reachable (ADR-0011)",
                        a.agentId, a.vaults.size, a.vaults, a.vaults.first(),
                    )
                }

                val ready = AtomicBoolean(false)
                // A backup remote set at runtime (PUT /settings/backup) is persisted per-engine and
                // takes precedence over the startup config, so it survives a restart.
                val backupStore = BackupConfigStore(vaults.default().engine.root)
                val effectiveBackup = backupStore.load()?.let { SvodConfig.BackupSettings(it.remote, it.enabled) } ?: config.backup
                val backup = dev.svod.engine.sync.BackupService(
                    vaults = vaults.contexts().map { dev.svod.engine.sync.BackupService.VaultRef(it.id, it.engine.root) },
                    config = effectiveBackup,
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
                    backupStore = backupStore,
                    syncConfig = { vc ->
                        val peers = config.syncRemotesFor(vc.id).map { dev.svod.engine.lifecycle.SvodConfig.redactRemote(it) }
                        dev.svod.engine.api.SyncConfigDto(
                            role = vc.syncStatus()?.role ?: "standalone",
                            hostId = config.hostIdFor(vc.id),
                            syncConfigured = peers.isNotEmpty(),
                            syncIntervalSeconds = config.syncIntervalSecondsFor(vc.id),
                            peers = peers,
                            backup = backup.config?.let { dev.svod.engine.api.BackupConfigDto(dev.svod.engine.lifecycle.SvodConfig.redactRemote(it.remote), it.enabled) },
                        )
                    },
                ).start(config.appApiPort)

                val mcpServer = dev.svod.engine.mcp.SvodMcpServer(toolsFor, registry, host = config.host)
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
