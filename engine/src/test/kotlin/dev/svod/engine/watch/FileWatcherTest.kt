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
import kotlinx.coroutines.runBlocking
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
}
