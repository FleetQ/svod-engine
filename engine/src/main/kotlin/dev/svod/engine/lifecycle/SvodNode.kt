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
    private val sourceScheduler: dev.svod.engine.sources.SourceScheduler,
    private val backupScheduler: dev.svod.engine.sync.BackupScheduler,
    private val syncScheduler: dev.svod.engine.sync.SyncScheduler,
    private val sourceWatch: dev.svod.engine.sources.SourceWatchManager,
    private val ready: AtomicBoolean,
    private val ownsScope: CoroutineScope,
) : AutoCloseable {

    val appApiPort: Int get() = api.port
    val mcpPort: Int get() = mcp.port
    fun isReady(): Boolean = ready.get()

    /** The default vault's engine (back-compat convenience for single-vault callers/tests). */
    val engine: SvodEngine get() = vaults.default().engine

    /** Trigger one reconcile across every vault (no-op where sync isn't configured). */
    suspend fun sync() {
        for (vc in vaults.contexts()) runSync(vaults, backup, vc.id)
    }

    @Volatile
    private var stopped = false

    fun shutdown() {
        if (stopped) return
        stopped = true
        ready.set(false)
        // 1. stop accepting new work
        runCatching { sourceScheduler.stop() }
        runCatching { backupScheduler.stop() }
        runCatching { syncScheduler.stop() }
        runCatching { sourceWatch.stop() }
        runCatching { api.stop() }
        runCatching { mcp.stop() }
        // 2. close every vault: watcher + peers, then index, then the engine (drains the queue).
        runCatching { vaults.close() }
    }

    override fun close() = shutdown()

    companion object {
        /**
         * Run one reconcile cycle for [id] when it is a synced vault (its remote resolves), recording
         * the success markers (lastSyncedAt/head) for the UI + restart. Returns null when [id] is not
         * a synced vault. Shared by POST /sync/now, the [SyncScheduler], and [sync].
         */
        private suspend fun runSync(
            vaults: VaultManager,
            backup: dev.svod.engine.sync.BackupService,
            id: String,
        ): dev.svod.engine.sync.SyncEngine.Result? {
            val remote = backup.syncRemote(id) ?: return null
            val vc = vaults.context(id) ?: return null
            val res = vc.sync(remote)
            if (res.status == dev.svod.engine.sync.SyncEngine.Status.inSync) {
                backup.recordSyncSuccess(id, res.head, res.lastSyncedAt ?: java.time.Instant.now().toString())
            }
            return res
        }

        fun start(config: SvodConfig, scope: CoroutineScope? = null, configPath: java.nio.file.Path? = null): SvodNode {
            val errors = config.validate()
            require(errors.isEmpty()) { "invalid config:\n - " + errors.joinToString("\n - ") }

            val workScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val eventBus = EventBus()
            // A stable, persistent per-machine id: commits record it as the committer and the conflict
            // UI shows "edited on <machineA> vs <machineB>". An explicit config.hostId still wins.
            val hostId = HostIdentity.resolve(config.hostId)
            val vaults = VaultManager.open(config, workScope, eventBus, hostId)
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
                    vc.id to SvodTools(vc.engine, vc.index, audit, rateLimiter, eventBus, graph = vc.graph)
                }

                val ready = AtomicBoolean(false)
                // Per-vault backup: each vault gets its own remote + persistence (a runtime PUT is
                // saved to that vault's .svod/backup.json and survives a restart, taking precedence
                // over the startup config; falls back to a per-vault or global config remote).
                val backup = dev.svod.engine.sync.BackupService(
                    vaults.contexts().map { vc ->
                        val store = BackupConfigStore(vc.engine.root)
                        val persisted = store.load()
                        // A persisted runtime config (incl. backup schedule + sync toggle) wins over startup.
                        val effective = persisted?.let {
                            SvodConfig.BackupSettings(it.remote, it.enabled, it.backupOnStartup, it.backupIntervalMinutes, it.backupOnChange, it.syncEnabled, it.syncIntervalMinutes)
                        } ?: config.backupFor(vc.id)
                        dev.svod.engine.sync.BackupService.Binding(
                            vc.id, vc.engine.root, effective, store,
                            persisted?.lastBackupAt, persisted?.lastBackupHead, persisted?.lastSyncedAt, persisted?.lastSyncedHead,
                        )
                    },
                )
                // Per-source filesystem auto-sync: a watcher re-syncs each autoSync source shortly after
                // its files change. Built before the API so the API can query "is it watching?" and ask
                // it to reconcile after a register/PATCH/remove.
                val sourceWatch = dev.svod.engine.sources.SourceWatchManager(
                    workScope, eventBus,
                    vaults.contexts().map { dev.svod.engine.sources.SourceWatchManager.Vault(it.id, it.engine, it.engine.root) },
                )

                val ecView = config.toEmbedderConfig()
                // One shared, mutable config holder so the runtime controllers (embedder, vault
                // creation) persist edits onto each other's state instead of clobbering.
                val configStore = ConfigStore(config, configPath)
                val embedderControl = EmbedderController(vaults, configStore)
                val vaultCreator = VaultController(vaults, configStore, workScope, eventBus, hostId)
                val agentController = AgentController(configStore, registry, config.host)
                val updateService = UpdateService(
                    currentAppVersion = "1.15.1",
                    releaseFetcher = UpdateService.productionFetcher(),
                )
                val api = AppApiServer(
                    vaults = vaults,
                    eventBus = eventBus,
                    config = AppApiServer.Config(
                        host = config.host,
                        embedderProvider = ecView.providerName,
                        embedderModel = ecView.modelName,
                        embedderEndpoint = ecView.endpointOrNull,
                        webViewerPath = config.webViewerPath,
                    ),
                    readiness = { ready.get() },
                    embedderControl = embedderControl,
                    vaultCreator = vaultCreator,
                    vaultRemover = vaultCreator,
                    agentAdmin = agentController,
                    updateAdmin = updateService,
                    backup = backup,
                    syncConfig = { vc ->
                        val b = backup.configOf(vc.id)
                        val synced = b?.isSynced() == true
                        val redacted = b?.let { dev.svod.engine.lifecycle.SvodConfig.redactRemote(it.remote) }
                        val st = vc.syncStatus()
                        dev.svod.engine.api.SyncConfigDto(
                            backupRemote = redacted,
                            backupEnabled = b?.enabled ?: false,
                            backupOnStartup = b?.backupOnStartup ?: false,
                            backupIntervalMinutes = b?.backupIntervalMinutes,
                            backupOnChange = b?.backupOnChange ?: false,
                            lastBackupAt = backup.lastBackupAt(vc.id),
                            lastBackupHead = backup.lastBackupHead(vc.id),
                            // When synced, the same remote IS the bidirectional bus → surface it as the peer.
                            syncPeers = if (synced && redacted != null) listOf(redacted) else config.syncRemotesFor(vc.id).map { dev.svod.engine.lifecycle.SvodConfig.redactRemote(it) },
                            role = if (synced) "synced" else config.roleFor(vc.id),
                            hostId = hostId,
                            syncEnabled = synced,
                            syncIntervalMinutes = b?.syncIntervalMinutes,
                            syncStatus = st?.syncStatus,
                            lastSyncedAt = backup.lastSyncedAt(vc.id),
                        )
                    },
                    vaultStatus = { vc ->
                        // A sync dot shows for vaults that have peers OR a backup/sync remote configured.
                        if (config.syncRemotesFor(vc.id).isEmpty() && backup.configOf(vc.id) == null) null
                        else {
                            val st = vc.syncStatus()
                            dev.svod.engine.api.SyncStatusDto(
                                role = if (backup.isSynced(vc.id)) "synced" else config.roleFor(vc.id),
                                lastHead = st?.lastHead,
                                conflicts = st?.conflicts ?: (vc.conflicts?.all()?.size ?: 0),
                                syncStatus = st?.syncStatus,
                                lastSyncedAt = backup.lastSyncedAt(vc.id),
                            )
                        }
                    },
                    syncNow = { vc -> runSync(vaults, backup, vc.id) },
                    sourceWatching = { vc, sourceId -> sourceWatch.isWatching(vc.id, sourceId) },
                    reconcileSourceWatchers = { vc -> sourceWatch.reconcile(vc.id) },
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

                // Optional automatic re-sync of registered external sources (on-startup + interval).
                val sourceScheduler = dev.svod.engine.sources.SourceScheduler(
                    workScope, config.sourceSync.onStartup, config.sourceSync.intervalMinutes,
                ) {
                    for (vc in vaults.contexts()) {
                        val store = dev.svod.engine.sources.ExternalSourceStore(vc.engine.root)
                        val sync = dev.svod.engine.sources.SourceSync(vc.engine, store)
                        for (s in store.list()) sync.sync(s)
                    }
                }
                sourceScheduler.start()

                // Optional automatic backup (on-startup + interval + on-change), per vault, driven by
                // the same runtime config the App API exposes. Reuses the backup service above.
                val backupScheduler = dev.svod.engine.sync.BackupScheduler(workScope, backup, eventBus)
                backupScheduler.start()

                // Two-way sync driver: startup + interval poll + on-change debounce, per synced vault.
                val syncScheduler = dev.svod.engine.sync.SyncScheduler(
                    workScope, backup, { id -> runSync(vaults, backup, id) }, eventBus,
                )
                syncScheduler.start()

                // Start the FS watchers for every autoSync source (the global SourceScheduler above stays
                // as a coarse polling safety-net for hosts where native watching doesn't fire).
                sourceWatch.start()

                ready.set(true)
                eventBus.publish(dev.svod.engine.events.EventTypes.ENGINE_STATUS) { put("status", "ready") }
                return SvodNode(config, vaults, eventBus, mcp, api, backup, sourceScheduler, backupScheduler, syncScheduler, sourceWatch, ready, workScope)
            } catch (t: Throwable) {
                vaults.close()
                throw t
            }
        }
    }
}
