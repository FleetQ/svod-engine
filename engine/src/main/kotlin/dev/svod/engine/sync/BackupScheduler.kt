package dev.svod.engine.sync

import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Background driver that runs automatic backups, mirroring [dev.svod.engine.sources.SourceScheduler]'s
 * lifecycle (a [scope]-owned [Job], started after the engine is ready and cancelled on shutdown).
 * It reuses [BackupService.backupNow], so every run pushes a vault's canonical branch to its own
 * `refs/svod/backup/<vault>` ref; a failure in one round is logged and never crashes the engine.
 *
 * Unlike the source scheduler the backup schedule is **per vault and runtime-mutable** (the macOS UI
 * sets it via PUT /api/v1/settings/backup), so this does not pre-decide a fixed cadence at start: it
 * wakes every [tickMillis] and re-reads each vault's current [BackupService.configOf]. Three triggers,
 * each gated on `enabled`:
 *  - `backupOnStartup` — one backup per vault shortly after start;
 *  - `backupIntervalMinutes` (>0) — back up when the last success is at least that old;
 *  - `backupOnChange` — back up [quietMillis] after the most recent vault write commit settles.
 * `backupNow` itself skips a run when one is already in flight or there is nothing new to push.
 */
class BackupScheduler(
    private val scope: CoroutineScope,
    private val backup: BackupService,
    /** Commit-event source for `backupOnChange`; null disables the change trigger (interval/startup still work). */
    private val eventBus: EventBus? = null,
    private val tickMillis: Long = 60_000L,
    private val quietMillis: Long = 90_000L,
) {
    private val log = LoggerFactory.getLogger(BackupScheduler::class.java)
    private var intervalJob: Job? = null
    private var changeJob: Job? = null
    private val debounce = mutableMapOf<String, Job>()

    fun start() {
        intervalJob = scope.launch {
            for (id in backup.vaultIds()) {
                val c = backup.configOf(id) ?: continue
                if (active(c) && c.backupOnStartup) run(id, "startup")
            }
            while (isActive) {
                delay(tickMillis)
                for (id in backup.vaultIds()) {
                    val c = backup.configOf(id) ?: continue
                    val interval = c.backupIntervalMinutes ?: 0
                    if (!active(c) || interval <= 0) continue
                    if (backup.dueForInterval(id, interval)) run(id, "scheduled")
                }
            }
        }
        if (eventBus != null) {
            // Debounced backup-on-change: each write commit (re)arms a per-vault timer; a vault that
            // keeps changing keeps pushing its timer out, so we back up only once writes go quiet.
            changeJob = scope.launch {
                eventBus.events.collect { ev ->
                    if (ev.type != EventTypes.COMMIT_CREATED) return@collect
                    val vault = (ev.data["vault"]?.jsonPrimitive?.content) ?: return@collect
                    val c = backup.configOf(vault) ?: return@collect
                    if (!active(c) || !c.backupOnChange) return@collect
                    debounce.remove(vault)?.cancel()
                    debounce[vault] = scope.launch {
                        delay(quietMillis)
                        run(vault, "on-change")
                    }
                }
            }
        }
    }

    /**
     * Auto-backup runs only when backup is enabled AND a remote is set (spec: both required) AND the
     * vault is NOT synced — a synced vault's two-way sync already pushes the canonical ref off-site,
     * so one-way backup is retired for it.
     */
    private fun active(c: dev.svod.engine.lifecycle.SvodConfig.BackupSettings): Boolean =
        c.enabled && c.remote.isNotBlank() && !c.isSynced()

    private suspend fun run(vaultId: String, reason: String) {
        runCatching { backup.backupNow(vaultId) }
            .onFailure { log.warn("auto-backup ($reason) of '$vaultId' failed", it) }
    }

    fun stop() {
        intervalJob?.cancel(); intervalJob = null
        changeJob?.cancel(); changeJob = null
        debounce.values.forEach { it.cancel() }
        debounce.clear()
    }
}
