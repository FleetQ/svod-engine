package dev.svod.engine.watch

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.index.SearchMode
import dev.svod.engine.index.SearchQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileWatcherTest {

    private class Rig(val root: Path) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SvodEngine.open(root, scope)
        val index = IndexService(root, root.resolve(".svod").resolve("index"), NoneEmbedder).start()
        val bus = EventBus()
        val watcher = FileWatcher(root, engine, index, bus)
        init { engine.onCommit { index.onCommit(it) } }
        override fun close() { watcher.close(); index.close(); engine.close() }
    }

    @Test
    fun `external working-tree edits are ingested, committed and indexed`() = runBlocking {
        Rig(Files.createTempDirectory("svod-watch-")).use { rig ->
            // a file appears OUTSIDE the engine (e.g. another editor)
            Files.writeString(rig.root.resolve("external.md"), "# External\nappeared outside the engine via editor")

            rig.watcher.ingestNow() // deterministic (no FS-event timing dependence)
            rig.index.waitIdle()

            val read = rig.engine.read("external.md")
            assertNotNull(read, "external file should be readable after ingest")
            assertEquals("external", rig.engine.history("external.md").first().authorName, "committed as external author")
            assertEquals(
                "external.md",
                rig.index.search(SearchQuery("appeared editor", mode = SearchMode.KEYWORD)).hits.firstOrNull()?.path,
                "external content must be indexed",
            )
        }
    }

    @Test
    fun `engine writes are not re-ingested (no feedback loop)`() = runBlocking {
        Rig(Files.createTempDirectory("svod-watch-")).use { rig ->
            rig.engine.write("via-engine.md", "# Engine\nwritten through the engine", expectedRevision = null, author = Author("ui", "ui@svod.local"))
            val headAfterWrite = rig.engine.head()

            // the watcher firing for the engine's own write must be a no-op (tree already clean)
            assertNull(rig.engine.ingestExternalChanges(), "engine writes leave a clean tree → nothing to ingest")
            assertEquals(headAfterWrite, rig.engine.head(), "no duplicate external commit")
        }
    }

    @Test
    fun `path-scoped ingest of an already-committed engine path is a no-op`() = runBlocking {
        // This is the hot path: the watcher fires for the engine's OWN write and resolves it to a
        // path-scoped ingest of that path. It must be a cheap no-op (no duplicate commit) — and,
        // unlike the old full-tree commitAll ingest, it never walks the whole working tree.
        Rig(Files.createTempDirectory("svod-watch-")).use { rig ->
            rig.engine.write("via-engine.md", "# Engine\nwritten through the engine", expectedRevision = null, author = Author("ui", "ui@svod.local"))
            val head = rig.engine.head()
            assertNull(rig.engine.ingestExternalChanges(listOf("via-engine.md")), "already-committed path → nothing to ingest")
            assertEquals(head, rig.engine.head(), "no duplicate commit")
        }
    }

    @Test
    fun `the watcher ingests a genuine external edit via FS events (path-scoped)`() = runBlocking {
        Rig(Files.createTempDirectory("svod-watch-")).use { rig ->
            rig.watcher.start()
            Files.writeString(rig.root.resolve("ext-fsevent.md"), "# Ext\nvia a real fs event")
            val read = eventually(8_000) { rig.engine.read("ext-fsevent.md") }
            assertNotNull(read, "an external edit must be ingested through the FS-event path")
            assertEquals("via a real fs event", read.text.lines().last())
        }
    }

    /**
     * The risk introduced by hashing on (mtime, size) instead of on CONTENT.
     *
     * The watcher registers with FileHasher.LAST_MODIFIED_TIME because content hashing read every
     * byte under the vault root at startup — 839 MB of Lucene index included — and cost 34.5 s of a
     * ~52 s cold boot. The trade is that two writes are told apart by timestamp and size rather than
     * by content, so this pins the case that would break first: a same-length rewrite, immediately
     * after the first one.
     */
    @Test
    fun `a same-length rewrite moments later is still ingested`() = runBlocking {
        Rig(Files.createTempDirectory("svod-watch-")).use { rig ->
            rig.watcher.start()
            val note = rig.root.resolve("rewrite.md")
            Files.writeString(note, "# R\naaaaaaaaaa")
            val first = eventually(8_000) { rig.engine.read("rewrite.md") }
            assertNotNull(first, "the first write must be ingested")

            // Same byte length, different content — the pair a coarse hash could conflate.
            Files.writeString(note, "# R\nbbbbbbbbbb")
            val second = eventually(8_000) {
                rig.engine.read("rewrite.md")?.takeIf { it.text.contains("bbbbbbbbbb") }
            }
            assertNotNull(second, "a same-length rewrite must not be mistaken for the previous content")
            assertEquals("bbbbbbbbbb", second.text.lines().last())
        }
    }

    private suspend fun <T> eventually(timeoutMs: Long, block: suspend () -> T?): T? =
        withTimeoutOrNull(timeoutMs) { var r = block(); while (r == null) { delay(100); r = block() }; r }
}
