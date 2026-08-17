package dev.svod.engine.graphrag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The periodic rebuild trigger.
 *
 * Tested through [GraphScheduler.tick] rather than by waiting on the timer: a test that sleeps for a
 * scheduling interval is either slow or lying about what it observed. The timer itself is three lines
 * copied from `SourceScheduler` and is exercised by [start does nothing when no trigger is configured].
 *
 * NB (`mem:kotlin-junit-silent-skip`): every test body is a block body, so JUnit collects them.
 */
class GraphSchedulerTest {

    private fun status(
        enabled: Boolean = true,
        state: String = GraphState.READY.name,
        attached: Int = 0,
        builtAt: Long? = 1_000_000L,
    ) = GraphStatus(
        state = state, enabled = enabled, attachedCount = attached, builtAt = builtAt, incremental = true,
    )

    /** Asks the trigger logic directly; the vault supplier is empty because it is not under test. */
    private fun decide(
        intervalMinutes: Int?,
        rebuildAfterAttached: Int?,
        status: GraphStatus,
        now: Long,
    ): Boolean {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sched = GraphScheduler(scope, intervalMinutes, rebuildAfterAttached) { emptyList() }
        return sched.isDue(status, now)
    }

    @Test
    fun `no trigger configured means nothing is ever due`() {
        assertTrue(!decide(null, null, status(attached = 10_000), now = Long.MAX_VALUE / 2))
        assertTrue(!decide(0, 0, status(attached = 10_000), now = Long.MAX_VALUE / 2))
    }

    @Test
    fun `the attached-note threshold fires at or above, and not below`() {
        // The pair is the point: either assertion alone would still pass with the comparison inverted.
        assertTrue(decide(null, 2, status(attached = 2), now = 1_000_000L), "at the threshold")
        assertTrue(decide(null, 2, status(attached = 9), now = 1_000_000L), "above the threshold")
        assertTrue(!decide(null, 5, status(attached = 2), now = 1_000_000L), "below the threshold")
    }

    @Test
    fun `the interval fires only once it has actually elapsed`() {
        val builtAt = 1_000_000L
        val tenMinutesLater = builtAt + 10 * 60_000L
        val thirtyMinutesLater = builtAt + 30 * 60_000L
        assertTrue(!decide(20, null, status(builtAt = builtAt), tenMinutesLater), "10m < 20m interval")
        assertTrue(decide(20, null, status(builtAt = builtAt), thirtyMinutesLater), "30m > 20m interval")
    }

    @Test
    fun `a graph that has never been built is due immediately when an interval is set`() {
        // Nothing to preserve, and the alternative is a vault that stays NOT_BUILT forever because
        // `builtAt` is null and null is not "recent".
        assertTrue(decide(60, null, status(builtAt = null), now = 0L))
    }

    @Test
    fun `a disabled or building graph is never rebuilt`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sched = GraphScheduler(scope, 1, 1) { emptyList() }
        assertTrue(!sched.shouldConsider(status(enabled = false, attached = 100)))
        assertTrue(!sched.shouldConsider(status(state = GraphState.BUILDING.name, attached = 100)))
        assertTrue(sched.shouldConsider(status(attached = 100)))
    }

    @Test
    fun `start launches a loop only when a trigger is configured`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger(0)

        // Asserted on the JOB, not on "nothing happened in 200 ms". The loop delays a full tick
        // (≥1 minute) before doing anything, so a time-based assertion passes whether or not the
        // guard exists — it could not fail for the reason it is named for.
        val unconfigured = GraphScheduler(scope, null, null) { calls.incrementAndGet(); emptyList() }
        unconfigured.start()
        assertTrue(!unconfigured.isRunning, "an unconfigured scheduler must not start a loop at all")
        assertEquals(0, calls.get(), "and must not enumerate the vaults")
        unconfigured.stop()

        val zeroed = GraphScheduler(scope, 0, 0) { emptyList() }
        zeroed.start()
        assertTrue(!zeroed.isRunning, "zeros are 'unset', not 'every zero minutes'")
        zeroed.stop()

        val configured = GraphScheduler(scope, null, 5) { emptyList() }
        configured.start()
        assertTrue(configured.isRunning, "a configured scheduler must actually run")
        configured.stop()
        assertTrue(!configured.isRunning, "stop() must cancel the loop")
    }
}
