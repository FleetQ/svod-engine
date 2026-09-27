package dev.svod.engine.sync

import dev.svod.engine.core.Coalescer
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Background driver for two-way sync, mirroring [BackupScheduler]: a [scope]-owned [Job] started
 * after the engine is ready and cancelled on shutdown. It keeps every *synced* vault fresh via
 * three triggers, plus POST /api/v1/sync/now ([syncNow]):
 *  - **startup** — one reconcile per synced vault shortly after start;
 *  - **interval poll** — reconcile when the last sync is at least `syncIntervalMinutes` old
 *    (default 3); re-reads each vault's CURRENT config every tick, so toggling sync via
 *    PUT /api/v1/settings/backup takes effect without a restart;
 *  - **on-change debounce** — reconcile [quietMillis] after the most recent local write settles,
 *    but at least every [maxWaitMillis] while writes never settle (this also finalizes a held-open
 *    merge once the user's resolve writes drain the conflicts).
 *
 * Every trigger goes through one [Coalescer], so at most one cycle runs per vault: a manual or poll
 * request joins the cycle in progress, and a commit during a cycle marks the vault dirty for exactly
 * one trailing cycle. A new commit never cancels a cycle (1.25.4); only [stop] does. Each cycle
 * reconciles the local HEAD as it was when the cycle started (see [SyncEngine]); commits that land
 * during it belong to the trailing cycle. Progress is published as `sync.started` / `sync.finished`.
 *
 * The actual cycle is delegated to [sync] (provided by the node, which resolves the remote and
 * records the success markers; null ⇒ not a synced vault). A failure is logged, never fatal.
 */
class SyncScheduler(
    private val scope: CoroutineScope,
    private val backup: BackupService,
    private val sync: suspend (vaultId: String) -> SyncEngine.Result?,
    private val eventBus: EventBus? = null,
    private val tickMillis: Long = 60_000L,
    private val quietMillis: Long = 5_000L,
    private val maxWaitMillis: Long = 60_000L,
) {
    private val log = LoggerFactory.getLogger(SyncScheduler::class.java)
    private var intervalJob: Job? = null
    private var changeJob: Job? = null
    private val cycles = Coalescer(scope, quietMillis, maxWaitMillis) { vault, trigger -> cycle(vault, trigger) }

    /** The cycle in progress for a vault: what started it and when (ISO-8601). */
    data class Run(val trigger: String, val startedAt: String)
    private val runs = ConcurrentHashMap<String, Run>()

    fun start() {
        intervalJob = scope.launch {
            for (id in backup.vaultIds()) if (backup.isSynced(id)) cycles.runNow(id, "startup").await()
            while (isActive) {
                delay(tickMillis)
                for (id in backup.vaultIds()) {
                    if (!backup.isSynced(id)) continue
                    if (backup.dueForSyncInterval(id, backup.syncIntervalMinutes(id))) cycles.runNow(id, "poll").await()
                }
            }
        }
        if (eventBus != null) {
            changeJob = scope.launch {
                eventBus.events.collect { ev ->
                    if (ev.type != EventTypes.COMMIT_CREATED) return@collect
                    val vault = (ev.data["vault"]?.jsonPrimitive?.content) ?: return@collect
                    if (backup.isSynced(vault)) cycles.changed(vault)
                }
            }
        }
    }

    /** Run a cycle for [vaultId] now, or join the one already running (never queues a second). */
    fun syncNow(vaultId: String): Deferred<SyncEngine.Result?> = cycles.runNow(vaultId, "manual")

    /** A cycle is in progress for [vaultId] (set the moment it is started, before [running] fills in). */
    fun isRunning(vaultId: String): Boolean = cycles.isRunning(vaultId)

    /** The cycle in progress for [vaultId], or null when idle. */
    fun running(vaultId: String): Run? = runs[vaultId]

    /** Another cycle is already due for [vaultId] (debounce armed, or it was written to mid-cycle). */
    fun pending(vaultId: String): Boolean = cycles.isPending(vaultId)

    private suspend fun cycle(vaultId: String, trigger: String): SyncEngine.Result? {
        if (!backup.isSynced(vaultId)) return null // nothing to reconcile, nothing to announce
        val startedMs = System.currentTimeMillis()
        runs[vaultId] = Run(trigger, Instant.ofEpochMilli(startedMs).toString())
        eventBus?.publish(EventTypes.SYNC_STARTED) { put("vault", vaultId); put("trigger", trigger) }
        var result: SyncEngine.Result? = null
        try {
            result = try {
                sync(vaultId)
            } catch (e: CancellationException) {
                throw e // shutdown, not a sync failure
            } catch (e: Exception) {
                log.warn("auto-sync ($trigger) of '$vaultId' failed", e)
                SyncEngine.Result(SyncEngine.Status.error, null, 0, null)
            }
            return result
        } finally {
            runs.remove(vaultId)
            val r = result
            if (r != null) eventBus?.publish(EventTypes.SYNC_FINISHED) {
                put("vault", vaultId); put("trigger", trigger)
                put("status", r.status.name); put("head", r.head ?: ""); put("conflicts", r.conflicts)
                r.lastSyncedAt?.let { put("lastSyncedAt", it) }
                put("durationMs", System.currentTimeMillis() - startedMs)
                put("pending", cycles.isPending(vaultId)) // a trailing cycle follows (written to mid-cycle)
            }
        }
    }

    fun stop() {
        intervalJob?.cancel(); intervalJob = null
        changeJob?.cancel(); changeJob = null
        cycles.stop()
    }
}
