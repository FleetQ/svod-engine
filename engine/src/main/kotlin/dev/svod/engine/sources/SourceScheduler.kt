package dev.svod.engine.sources

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Background driver that re-syncs registered external sources automatically: optionally once at
 * startup, then on a fixed interval. With both off it does nothing — sources sync only when an
 * endpoint is called. [syncAll] does the work (the node wires it to "every source of every vault");
 * a failure in one round is logged and the loop continues to the next tick.
 */
class SourceScheduler(
    private val scope: CoroutineScope,
    private val onStartup: Boolean,
    private val intervalMinutes: Int?,
    private val syncAll: suspend () -> Unit,
) {
    private val log = LoggerFactory.getLogger(SourceScheduler::class.java)
    private var job: Job? = null

    fun start() {
        val interval = intervalMinutes ?: 0
        if (!onStartup && interval <= 0) return
        job = scope.launch {
            if (onStartup) runCatching { syncAll() }.onFailure { log.warn("source sync (startup) failed", it) }
            if (interval > 0) {
                while (isActive) {
                    delay(interval.toLong() * 60_000L)
                    runCatching { syncAll() }.onFailure { log.warn("source sync (scheduled) failed", it) }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
