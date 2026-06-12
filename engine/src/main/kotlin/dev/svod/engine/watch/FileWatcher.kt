package dev.svod.engine.watch

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.index.IndexService
import io.methvin.watcher.DirectoryWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * Watches the vault for changes made OUTSIDE the engine (a user editing in another app, a
 * git pull) and folds them into history + the index.
 *
 * Events are debounced and funneled into a single ingest that runs on the write-actor
 * ([SvodEngine.ingestExternalChanges]). Because ingest is actor-serialized, it can never
 * race an engine write: a change the engine itself made is already committed, so ingest is a
 * no-op for it — no double-commits, no feedback loop. Only genuine external edits produce a
 * commit + `file.changed` / `commit.created` events.
 */
class FileWatcher(
    private val root: Path,
    private val engine: SvodEngine,
    private val index: IndexService,
    private val eventBus: EventBus,
    private val debounceMs: Long = 300,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private var watcher: DirectoryWatcher? = null

    fun start(): FileWatcher {
        watcher = DirectoryWatcher.builder()
            .path(root)
            .listener { event -> if (!isIgnored(event.path())) trigger.trySend(Unit) }
            .build()
        watcher!!.watchAsync()
        scope.launch {
            for (signal in trigger) {
                delay(debounceMs) // coalesce a burst of edits into one ingest
                ingest()
            }
        }
        return this
    }

    /** Force a debounce-free ingest (used by tests / explicit refresh). */
    suspend fun ingestNow() = ingest()

    private suspend fun ingest() {
        val commit = engine.ingestExternalChanges() ?: return
        index.onCommit(commit)
        eventBus.publish(EventTypes.FILE_CHANGED) { put("source", "watcher"); put("commit", commit) }
        eventBus.publish(EventTypes.COMMIT_CREATED) { put("commit", commit); put("author", "external") }
    }

    private fun isIgnored(path: Path): Boolean {
        val top = root.relativize(path).toString().replace('\\', '/').substringBefore('/')
        return top == ".git" || top == ".svod"
    }

    override fun close() {
        watcher?.close()
        trigger.close()
        scope.cancel()
    }
}
