package dev.svod.engine.graphrag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Background driver for the periodic FULL graph rebuild.
 *
 * **Why this exists.** Incremental attachment (v1.17.0) keeps new notes reachable between builds but
 * deliberately does not recompute the partition — the design's answer to the resulting drift is "a
 * periodic full rebuild restores the truth". Until now nothing performed one: `rebuildOnStartup` is
 * off by default, there was no timer, and the only triggers were a button and an endpoint. The trade
 * was stated and unbacked.
 *
 * Mirrors [dev.svod.engine.sources.SourceScheduler]: same scope/job shape, same "log the failure and
 * keep ticking" behaviour. Both triggers null ⇒ [start] launches nothing, which is the default.
 */
class GraphScheduler(
    private val scope: CoroutineScope,
    /** Elapsed minutes between forced rebuilds. Null/≤0 ⇒ time alone never triggers one. */
    private val intervalMinutes: Int?,
    /** Attached-note count at or above which a rebuild is due. Null/≤0 ⇒ drift alone never triggers one. */
    private val rebuildAfterAttached: Int?,
    /**
     * The candidate vaults. Each is asked for its own status, so one vault being busy or disabled
     * never holds up another.
     */
    private val services: () -> List<GraphService>,
) {
    private val log = LoggerFactory.getLogger(GraphScheduler::class.java)
    private var job: Job? = null

    /**
     * Whether [start] actually launched a loop. Exposed for tests: asserting "nothing happened within
     * 200 ms" cannot distinguish a scheduler that refused to start from one that started and is
     * simply sleeping out its first (≥1 minute) tick.
     */
    internal val isRunning: Boolean get() = job?.isActive == true

    /** How often the loop wakes to evaluate the triggers. */
    private val tickMinutes: Long
        get() = minOf(intervalMinutes?.takeIf { it > 0 }?.toLong() ?: TICK_MINUTES, TICK_MINUTES)

    fun start() {
        val interval = intervalMinutes ?: 0
        val threshold = rebuildAfterAttached ?: 0
        if (interval <= 0 && threshold <= 0) return
        job = scope.launch {
            while (isActive) {
                delay(tickMinutes * 60_000L)
                runCatching { tick() }.onFailure { log.warn("graph rebuild tick failed", it) }
            }
        }
    }

    /**
     * One evaluation round. Visible for tests, which drive it directly rather than waiting on a timer.
     *
     * @return how many rebuilds were started.
     */
    internal fun tick(now: Long = System.currentTimeMillis()): Int {
        var started = 0
        for (g in services()) {
            val status = runCatching { g.status() }.getOrNull() ?: continue
            if (!shouldConsider(status)) continue
            val reason = dueReason(status, now) ?: continue
            // rebuild() refuses while a build is running, so a tick that races one costs nothing.
            if (g.rebuild()) {
                started++
                log.info("graph rebuild started: $reason")
            }
        }
        return started
    }

    /**
     * Whether a vault is even a candidate this tick: the feature is on and no build is in flight.
     * A disabled graph must never be built behind the operator's back.
     */
    internal fun shouldConsider(status: GraphStatus): Boolean =
        status.enabled && status.state != GraphState.BUILDING.name

    /** [dueReason] as a predicate, for tests that assert the trigger rather than the log line. */
    internal fun isDue(status: GraphStatus, now: Long): Boolean = dueReason(status, now) != null

    /**
     * Why a rebuild is due, or null when it is not.
     *
     * **Two triggers, not one.** A rebuild is ~15 minutes of local LLM time, so firing it on a vault
     * that has not moved is pure waste — the attached-note threshold is the honest signal. But drift
     * can also be slow and steady, and a vault that never crosses the threshold would never be
     * rebuilt at all, so the interval is the safety net. Either is enough.
     */
    private fun dueReason(status: GraphStatus, now: Long): String? {
        val threshold = rebuildAfterAttached ?: 0
        if (threshold > 0 && status.attachedCount >= threshold) {
            return "${status.attachedCount} notes attached since the last build (threshold $threshold)"
        }
        val interval = intervalMinutes ?: 0
        val builtAt = status.builtAt
        if (interval > 0) {
            // Never built ⇒ the interval has trivially elapsed; there is nothing to preserve.
            if (builtAt == null) return "no build on record"
            val elapsed = (now - builtAt) / 60_000L
            if (elapsed >= interval) return "${elapsed}m since the last build (interval ${interval}m)"
        }
        return null
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        /**
         * Upper bound on how long the loop sleeps between evaluations. The threshold trigger has no
         * clock of its own, so the loop must wake often enough to notice drift without the operator
         * also having to set an interval.
         */
        const val TICK_MINUTES = 15L
    }
}
