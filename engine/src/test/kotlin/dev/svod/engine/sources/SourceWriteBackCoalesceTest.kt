package dev.svod.engine.sources

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceWriteBackCoalesceTest {

    /**
     * Regression (same defect as SyncScheduler in 1.25.4, fixed here in 1.26.0): every vault commit
     * cancelled the write-back job, and the source sync ran INSIDE it — a commit during a slow
     * write-back killed it mid-way. Now a commit during a write-back only earns one trailing run.
     */
    @Test
    fun `a commit during a running write-back never cancels it and earns one trailing run`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val root = Files.createTempDirectory("svod-writeback-")
        val engine = SvodEngine.open(root, scope)
        val bus = EventBus()
        val started = AtomicInteger(); val completed = AtomicInteger(); val cancelled = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val manager = SourceWatchManager(scope, bus, listOf(SourceWatchManager.Vault("v", engine, root)),
            debounceMs = 20, superviseMs = 3_600_000) {
            val n = started.incrementAndGet()
            try { if (n == 1) gate.await(); completed.incrementAndGet() }
            catch (e: CancellationException) { cancelled.incrementAndGet(); throw e }
        }
        fun commit() = bus.publish(EventTypes.COMMIT_CREATED) { put("vault", "v"); put("author", "ui") }
        try {
            manager.start()
            delay(200) // the collector subscribes (no replay)
            commit()
            withTimeout(5_000) { while (started.get() < 1) delay(5) } // the write-back is running
            repeat(10) { commit(); delay(20) }
            gate.complete(Unit)
            withTimeout(5_000) { while (completed.get() < 2) delay(10) }
            delay(300)
            assertEquals(0, cancelled.get(), "a commit must never cancel a running write-back")
            assertEquals(2, started.get(), "the running write-back + exactly one trailing run")
        } finally {
            manager.stop(); engine.close(); scope.cancel()
        }
    }
}
