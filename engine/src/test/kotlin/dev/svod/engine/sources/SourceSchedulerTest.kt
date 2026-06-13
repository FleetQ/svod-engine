package dev.svod.engine.sources

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceSchedulerTest {

    @Test
    fun `runs syncAll once on startup`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val count = AtomicInteger(0)
        val done = CompletableDeferred<Unit>()
        val sched = SourceScheduler(scope, onStartup = true, intervalMinutes = null) {
            count.incrementAndGet(); done.complete(Unit)
        }
        sched.start()
        withTimeout(5_000) { done.await() }
        sched.stop()
        assertEquals(1, count.get())
        scope.cancel()
    }

    @Test
    fun `is a no-op when on-startup is off and interval is zero`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val count = AtomicInteger(0)
        val sched = SourceScheduler(scope, onStartup = false, intervalMinutes = 0) { count.incrementAndGet() }
        sched.start()
        delay(200)
        sched.stop()
        assertEquals(0, count.get())
        scope.cancel()
    }
}
