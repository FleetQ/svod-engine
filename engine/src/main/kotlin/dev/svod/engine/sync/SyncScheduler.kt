package dev.svod.engine.sync

import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Background driver for two-way sync, mirroring [BackupScheduler]: a [scope]-owned [Job] started
 * after the engine is ready and cancelled on shutdown. It keeps every *synced* vault fresh via
 * three triggers (sync also runs on demand via POST /api/v1/sync/now):
 *  - **startup** — one reconcile per synced vault shortly after start;
 *  - **interval poll** — reconcile when the last sync is at least `syncIntervalMinutes` old
 *    (default 3); re-reads each vault's CURRENT config every tick, so toggling sync via
 *    PUT /api/v1/settings/backup takes effect without a restart;
 *  - **on-change debounce** — reconcile [quietMillis] after the most recent local write settles
 *    (this also finalizes a held-open merge once the user's resolve writes drain the conflicts).
 *    A new write restarts only the pending quiet-period delay, never a cycle already running: on a
 *    large vault one cycle outlasts the gap between writes, and cancelling it restarted the sync
 *    over and over (1.25.4). A write during a running cycle marks the vault dirty, and exactly one
 *    follow-up is debounced once that cycle ends.
 *
 * The actual cycle is delegated to [sync] (provided by the node, which resolves the remote and
 * records the success markers). A failure in one round is logged and never crashes the engine.
 */
class SyncScheduler(
    private val scope: CoroutineScope,
    private val backup: BackupService,
    private val sync: suspend (vaultId: String) -> Unit,
    private val eventBus: EventBus? = null,
    private val tickMillis: Long = 60_000L,
    private val quietMillis: Long = 5_000L,
) {
    private val log = LoggerFactory.getLogger(SyncScheduler::class.java)
    private var intervalJob: Job? = null
    private var changeJob: Job? = null
    private val debounce = mutableMapOf<String, Job>()   // vault → pending quiet-period delay
    private val running = mutableMapOf<String, Job>()     // vault → on-change cycle in progress
    private val dirty = mutableSetOf<String>()            // written to while their cycle was running

    fun start() {
        intervalJob = scope.launch {
            for (id in backup.vaultIds()) if (backup.isSynced(id)) run(id, "startup")
            while (isActive) {
                delay(tickMillis)
                for (id in backup.vaultIds()) {
                    if (!backup.isSynced(id)) continue
                    if (backup.dueForSyncInterval(id, backup.syncIntervalMinutes(id))) run(id, "poll")
                }
            }
        }
        if (eventBus != null) {
            changeJob = scope.launch {
                eventBus.events.collect { ev ->
                    if (ev.type != EventTypes.COMMIT_CREATED) return@collect
                    val vault = (ev.data["vault"]?.jsonPrimitive?.content) ?: return@collect
                    if (!backup.isSynced(vault)) return@collect
                    synchronized(this@SyncScheduler) {
                        if (vault in running) dirty += vault else scheduleOnChange(vault)
                    }
                }
            }
        }
    }

    /** (Re)start [vault]'s quiet-period delay. Caller holds the monitor. */
    private fun scheduleOnChange(vault: String) {
        debounce.remove(vault)?.cancel()
        debounce[vault] = scope.launch {
            delay(quietMillis)
            val me = coroutineContext[Job]!!
            synchronized(this@SyncScheduler) {
                if (debounce[vault] !== me) return@launch // superseded by a later write
                debounce.remove(vault)
                running[vault] = me
            }
            try {
                run(vault, "on-change")
            } finally {
                synchronized(this@SyncScheduler) {
                    running -= vault
                    if (dirty.remove(vault) && isActive) scheduleOnChange(vault)
                }
            }
        }
    }

    private suspend fun run(vaultId: String, reason: String) {
        try {
            sync(vaultId)
        } catch (e: CancellationException) {
            throw e // shutdown, not a sync failure
        } catch (e: Exception) {
            log.warn("auto-sync ($reason) of '$vaultId' failed", e)
        }
    }

    fun stop() {
        intervalJob?.cancel(); intervalJob = null
        changeJob?.cancel(); changeJob = null
        synchronized(this) {
            debounce.values.forEach { it.cancel() }
            debounce.clear()
            running.values.forEach { it.cancel() }
            dirty.clear()
        }
    }
}
