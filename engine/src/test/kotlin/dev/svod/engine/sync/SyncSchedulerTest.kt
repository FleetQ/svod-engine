package dev.svod.engine.sync

import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.lifecycle.SvodConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncSchedulerTest {

    /**
     * Regression (engine 1.25.4, 2026-09-27): every COMMIT_CREATED cancelled the on-change job, and
     * the sync cycle ran INSIDE that job — so on a large vault, where one cycle outlasts the gap
     * between writes, each new commit killed the running sync ("auto-sync (on-change) failed:
     * JobCancellationException") and restarted it. POST /sync/now queued behind that churn until
     * the app timed out. A commit may only restart the pending quiet-period delay; a commit during
     * a running cycle must coalesce into ONE follow-up cycle.
     */
    @Test
    fun `commits during a slow on-change sync never cancel it and coalesce into one follow-up`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus()
        val backup = BackupService(listOf(BackupService.Binding(
            "v", Files.createTempDirectory("svod-syncsched-"),
            SvodConfig.BackupSettings("unused-remote", enabled = true, syncEnabled = true), store = null,
        )))
        val mutex = Mutex() // SyncEngine.sync serializes cycles per vault the same way
        val started = AtomicInteger()
        val completed = AtomicInteger()
        val cancelled = AtomicInteger()
        val slowSync: suspend (String) -> Unit = {
            mutex.withLock {
                started.incrementAndGet()
                try { delay(600); completed.incrementAndGet() }
                catch (e: CancellationException) { cancelled.incrementAndGet(); throw e }
            }
        }
        val sched = SyncScheduler(scope, backup, slowSync, bus, tickMillis = 3_600_000, quietMillis = 50)
        fun commit() = bus.publish(EventTypes.COMMIT_CREATED) { put("vault", "v"); put("author", "agent") }
        try {
            sched.start()
            withTimeout(5_000) { while (completed.get() < 1) delay(10) } // the startup cycle
            delay(200) // let the scheduler's COMMIT_CREATED collector subscribe (the bus has no replay)

            commit()
            withTimeout(5_000) { while (started.get() < 2) delay(5) } // on-change cycle is now running
            repeat(10) { commit(); delay(40) }                       // writes keep landing mid-sync

            // A manual POST /sync/now must get its turn within about one cycle, not wait indefinitely.
            withTimeout(5_000) { slowSync("v") }

            withTimeout(10_000) { while (completed.get() < started.get()) delay(10) }
            delay(400) // anything that would still be scheduled has had time to start
            assertEquals(0, cancelled.get(), "a new commit must never cancel a sync already in progress")
            assertEquals(started.get(), completed.get(), "every started cycle ran to completion")
            // startup + the on-change cycle + ONE coalesced follow-up + the manual sync/now.
            assertTrue(started.get() <= 4, "commits during a running cycle coalesce, not pile up (ran ${started.get()})")
            assertTrue(started.get() >= 4, "the commits that landed mid-sync still get a follow-up cycle (ran ${started.get()})")
        } finally {
            sched.stop(); scope.cancel()
        }
    }

    @Test
    fun `commits during the quiet period restart only the delay and yield a single sync`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus()
        val backup = BackupService(listOf(BackupService.Binding(
            "v", Files.createTempDirectory("svod-syncsched-"),
            SvodConfig.BackupSettings("unused-remote", enabled = true, syncEnabled = true), store = null,
        )))
        val runs = AtomicInteger()
        val sched = SyncScheduler(scope, backup, { runs.incrementAndGet() }, bus, tickMillis = 3_600_000, quietMillis = 200)
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
}
