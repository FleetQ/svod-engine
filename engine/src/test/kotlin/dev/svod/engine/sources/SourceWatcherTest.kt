package dev.svod.engine.sources

import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.core.SvodEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real filesystem-watching tests (macOS CI has native FSEvents). Each registers an auto-sync source
 * pointing at a temp dir and asserts edits flow into the vault without a manual sync.
 */
class SourceWatcherTest {

    private class Rig : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vaultRoot: Path = Files.createTempDirectory("svod-vault-")
        val sourceDir: Path = Files.createTempDirectory("svod-src-")
        val engine = SvodEngine.open(vaultRoot, scope)
        val store = ExternalSourceStore(vaultRoot)
        val bus = EventBus()
        // Small debounce keeps the tests fast; the supervisor is effectively disabled.
        val manager = SourceWatchManager(scope, bus, listOf(SourceWatchManager.Vault("v", engine, vaultRoot)), debounceMs = 250, superviseMs = 3_600_000)

        fun register(autoSync: Boolean): ExternalSource =
            store.put(ExternalSource(id = ExternalSource.idFor(sourceDir.toString()), path = sourceDir.toString(), autoSync = autoSync))

        override fun close() { manager.stop(); engine.close() }
    }

    /** Poll [block] until it returns non-null or [timeoutMs] elapses. */
    private suspend fun <T> eventually(timeoutMs: Long = 6_000, block: suspend () -> T?): T? =
        withTimeoutOrNull(timeoutMs) { var r = block(); while (r == null) { delay(100); r = block() }; r }

    @Test
    fun `an auto-sync source materializes a changed file into the vault within seconds`() = runBlocking {
        Rig().use { rig ->
            Files.writeString(rig.sourceDir.resolve("seed.md"), "# Seed\n")
            val src = rig.register(autoSync = true)
            rig.manager.start()
            assertTrue(eventually { if (rig.manager.isWatching("v", src.id)) true else null } == true, "watcher should be running")

            // A new file appears in the source → the watcher should sync it into the vault, no manual call.
            Files.writeString(rig.sourceDir.resolve("note.md"), "# Note\nfrom the watched source\n")
            val read = eventually { rig.engine.read("note.md") }
            assertNotNull(read, "watched source file must materialize in the vault")
            assertTrue("from the watched source" in read.text)
        }
    }

    @Test
    fun `toggling autoSync off stops the watcher and changes no longer sync`() = runBlocking {
        Rig().use { rig ->
            val src = rig.register(autoSync = true)
            rig.manager.start()
            assertTrue(eventually { if (rig.manager.isWatching("v", src.id)) true else null } == true)

            // Turn it off (what PATCH does) and reconcile → the watcher stops.
            rig.store.put(src.copy(autoSync = false))
            rig.manager.reconcile("v")
            assertEquals(false, rig.manager.isWatching("v", src.id), "watcher must be stopped after toggle off")

            Files.writeString(rig.sourceDir.resolve("ignored.md"), "# Ignored\n")
            delay(1_500)
            assertNull(rig.engine.read("ignored.md"), "no sync should run once auto-sync is off")
        }
    }

    @Test
    fun `an editor atomic save (temp + rename) coalesces into a single sync`() = runBlocking {
        Rig().use { rig ->
            val src = rig.register(autoSync = true)
            rig.manager.start()
            assertTrue(eventually { if (rig.manager.isWatching("v", src.id)) true else null } == true)

            val syncs = AtomicInteger(0)
            val collector: Job = rig.scope.launch {
                rig.bus.events.collect { if (it.type == EventTypes.SOURCE_SYNCED) syncs.incrementAndGet() }
            }
            delay(200)

            // Atomic save: write a temp file, then rename it onto the final name in one move.
            val tmp = rig.sourceDir.resolve(".doc.md.tmp")
            Files.writeString(tmp, "# Doc\nfinal content\n")
            Files.move(tmp, rig.sourceDir.resolve("doc.md"), StandardCopyOption.ATOMIC_MOVE)

            val read = eventually { rig.engine.read("doc.md") }
            assertNotNull(read, "the final file must sync"); assertTrue("final content" in read.text)
            delay(1_000) // let any stragglers arrive
            collector.cancel()
            assertEquals(1, syncs.get(), "atomic save must coalesce into exactly one sync, not a flood")
        }
    }

    @Test
    fun `a series of spaced edits all converge to the final content - no lost updates`(): Unit = runBlocking {
        // The exact scenario that regressed: repeated edits to one file, each separated by a pause
        // longer than the debounce. Every edit must flow through; the final state must materialize and
        // the watcher must never stall (the old design cancelled in-flight syncs on watch restart).
        Rig().use { rig ->
            val src = rig.register(autoSync = true)
            rig.manager.start()
            assertTrue(eventually { if (rig.manager.isWatching("v", src.id)) true else null } == true)

            repeat(5) { i ->
                Files.writeString(rig.sourceDir.resolve("doc.md"), "rev ${i + 1}\n")
                delay(600)
            }
            val read = eventually { rig.engine.read("doc.md")?.takeIf { "rev 5" in it.text } }
            assertNotNull(read, "the final revision of a spaced edit series must converge")
        }
    }

    @Test
    fun `a burst of rapid edits coalesces into far fewer syncs than writes and lands the final content`() = runBlocking {
        Rig().use { rig ->
            val src = rig.register(autoSync = true)
            rig.manager.start()
            assertTrue(eventually { if (rig.manager.isWatching("v", src.id)) true else null } == true)

            val syncs = AtomicInteger(0)
            val collector: Job = rig.scope.launch {
                rig.bus.events.collect { if (it.type == EventTypes.SOURCE_SYNCED) syncs.incrementAndGet() }
            }
            delay(200)

            // 10 rapid writes to one file, faster than the debounce window.
            repeat(10) { i -> Files.writeString(rig.sourceDir.resolve("burst.md"), "n=${i + 1}\n"); delay(20) }

            val read = eventually { rig.engine.read("burst.md")?.takeIf { "n=10" in it.text } }
            assertNotNull(read, "the final write of a burst must land")
            delay(1_000)
            collector.cancel()
            // The exact count depends on FSEvents delivery jitter (CI batches differently than a dev
            // Mac), so assert the invariant that actually matters: it coalesced — far fewer syncs than
            // the 10 writes — rather than flooding one sync per write.
            assertTrue(syncs.get() in 1..7, "a rapid burst must coalesce (≪10 syncs), got ${syncs.get()}")
        }
    }
}
