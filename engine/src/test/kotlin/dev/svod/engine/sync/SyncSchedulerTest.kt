package dev.svod.engine.sync

import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.lifecycle.SvodConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyncSchedulerTest {

    private val ok = SyncEngine.Result(SyncEngine.Status.inSync, "head", 0, null)

    private fun syncedBackup() = BackupService(listOf(BackupService.Binding(
        "v", Files.createTempDirectory("svod-syncsched-"),
        SvodConfig.BackupSettings("unused-remote", enabled = true, syncEnabled = true), store = null,
    )))

    /**
     * Regression (engine 1.25.4, 2026-09-27): every COMMIT_CREATED cancelled the on-change job, and
     * the sync cycle ran INSIDE that job — so on a large vault, where one cycle outlasts the gap
     * between writes, each new commit killed the running sync ("auto-sync (on-change) failed:
     * JobCancellationException") and restarted it. POST /sync/now queued behind that churn until
     * the app timed out. A commit may only restart the pending quiet-period delay; commits during
     * a running cycle coalesce into ONE trailing cycle; a manual sync/now joins the running cycle.
     */
    @Test
    fun `commits during a slow sync never cancel it, coalesce into one trailing cycle, and sync-now joins it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus()
        val started = AtomicInteger()
        val completed = AtomicInteger()
        val cancelled = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        // The on-change cycle (call #2) is held open on a gate until every mid-sync commit has
        // landed, so the test does not depend on runner speed; the other cycles are short.
        val gate = CompletableDeferred<Unit>()
        val slowSync: suspend (String) -> SyncEngine.Result? = {
            val n = started.incrementAndGet()
            maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try { if (n == 2) gate.await() else delay(100); completed.incrementAndGet(); ok }
            catch (e: CancellationException) { cancelled.incrementAndGet(); throw e }
            finally { active.decrementAndGet() }
        }
        val sched = SyncScheduler(scope, syncedBackup(), slowSync, bus, tickMillis = 3_600_000, quietMillis = 50)
        fun commit() = bus.publish(EventTypes.COMMIT_CREATED) { put("vault", "v"); put("author", "agent") }
        try {
            sched.start()
            withTimeout(5_000) { while (completed.get() < 1) delay(10) } // the startup cycle
            delay(200) // let the scheduler's COMMIT_CREATED collector subscribe (the bus has no replay)

            commit()
            withTimeout(5_000) { while (started.get() < 2) delay(5) } // on-change cycle is now running
            repeat(10) { commit(); delay(40) }                       // writes keep landing mid-sync
            // A manual POST /sync/now arrives while the cycle is still running: it joins that cycle.
            val syncNow = sched.syncNow("v")
            assertSame(syncNow, sched.syncNow("v"), "a second request joins the same cycle")
            delay(100)
            assertEquals(2, started.get(), "sync/now must not start a second, concurrent cycle")
            gate.complete(Unit)
            assertEquals(ok, withTimeout(5_000) { syncNow.await() }, "sync/now answers when the running cycle ends")

            withTimeout(10_000) { while (started.get() < 3 || completed.get() < started.get()) delay(10) }
            delay(400) // anything that would still be scheduled has had time to start
            assertEquals(0, cancelled.get(), "a new commit must never cancel a sync already in progress")
            assertEquals(1, maxActive.get(), "at most one cycle per vault at any time")
            // startup + the on-change cycle (joined by sync/now) + ONE coalesced trailing cycle.
            assertEquals(3, started.get(), "commits during a running cycle coalesce into exactly one trailing cycle")
        } finally {
            sched.stop(); scope.cancel()
        }
    }

    @Test
    fun `commits during the quiet period restart only the delay and yield a single sync`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus()
        val runs = AtomicInteger()
        val sched = SyncScheduler(scope, syncedBackup(), { runs.incrementAndGet(); ok }, bus, tickMillis = 3_600_000, quietMillis = 200)
        try {
            sched.start()
            withTimeout(5_000) { while (runs.get() < 1) delay(10) }
            delay(200) // let the scheduler's collector subscribe (the bus has no replay)
            repeat(5) { bus.publish(EventTypes.COMMIT_CREATED) { put("vault", "v") }; delay(30) }
            delay(600)
            assertEquals(2, runs.get(), "startup + exactly one debounced on-change sync")
        } finally {
            sched.stop(); scope.cancel()
        }
    }

    /**
     * A write every 2 s never lets the 5 s quiet period elapse. Without a max-wait the on-change
     * sync would never run while the writes go on; with it, it runs at least every 60 s.
     */
    @Test
    fun `constant writes cannot starve sync - max-wait forces a cycle`() = runTest {
        suspend fun onChangeRunsDuringWrites(maxWaitMillis: Long): List<Long> {
            val bus = EventBus()
            val at = CopyOnWriteArrayList<Long>()
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + job)
            val sched = SyncScheduler(scope, syncedBackup(), { at += testScheduler.currentTime; ok }, bus,
                tickMillis = 3_600_000, quietMillis = 5_000, maxWaitMillis = maxWaitMillis)
            sched.start()
            runCurrent() // startup cycle + the collector subscribes
            at.clear()
            val t0 = testScheduler.currentTime
            repeat(90) { bus.publish(EventTypes.COMMIT_CREATED) { put("vault", "v") }; advanceTimeBy(2_000) } // 180 s
            sched.stop(); job.cancel()
            return at.map { it - t0 }
        }

        val starved = onChangeRunsDuringWrites(Long.MAX_VALUE)
        assertEquals(emptyList(), starved, "control: debounce alone never fires under constant writes")

        val capped = onChangeRunsDuringWrites(60_000)
        assertTrue(capped.size >= 2, "max-wait fires while writes never settle: $capped")
        assertTrue(capped.first() <= 60_000, "first forced cycle within max-wait: $capped")
        capped.zipWithNext().forEach { (a, b) -> assertTrue(b - a <= 62_000, "cycles at most ~max-wait apart: $capped") }
    }

    @Test
    fun `sync events report start and finish with the trigger`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus()
        val events = CopyOnWriteArrayList<Pair<String, String>>()
        val collector = scope.launch {
            bus.events.collect { if (it.type.startsWith("sync.")) events += it.type to it.data["trigger"].toString() }
        }
        val sched = SyncScheduler(scope, syncedBackup(), { ok }, bus, tickMillis = 3_600_000)
        try {
            delay(100)
            sched.start()
            withTimeout(5_000) { while (events.none { it.first == EventTypes.SYNC_FINISHED }) delay(10) } // startup done
            withTimeout(5_000) { sched.syncNow("v").await() }
            withTimeout(5_000) { while (events.count { it.first == EventTypes.SYNC_FINISHED } < 2) delay(10) }
            assertEquals(listOf(EventTypes.SYNC_STARTED, EventTypes.SYNC_FINISHED, EventTypes.SYNC_STARTED, EventTypes.SYNC_FINISHED), events.map { it.first })
            assertEquals(listOf("\"startup\"", "\"startup\"", "\"manual\"", "\"manual\""), events.map { it.second })
        } finally {
            collector.cancel(); sched.stop(); scope.cancel()
        }
    }
}
