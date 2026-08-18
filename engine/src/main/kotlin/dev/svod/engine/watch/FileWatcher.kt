package dev.svod.engine.watch

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.index.IndexService
import io.methvin.watcher.DirectoryWatcher
import io.methvin.watcher.hashing.FileHasher
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
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var watcher: DirectoryWatcher? = null

    fun start(): FileWatcher {
        watcher = DirectoryWatcher.builder()
            .path(root)
            // Hash by (mtime, size), not by CONTENT. The default content hasher reads every byte
            // under the watched root at registration — including `.git` (97 MB) and `.svod` (839 MB
            // of Lucene index on the operator's vault), neither of which this watcher ever acts on.
            // Measured on that vault: registration 6,140 ms → 152 ms warm, and it was 34.5 s of a
            // ~52 s cold boot, the single largest phase. A 2-note vault paid 4.7 s for the same
            // reason, which is what showed the cost was the INDEX, not the notes.
            //
            // Deliberately not `fileHashing(false)` (104 ms, marginally faster): hashing is what
            // suppresses a duplicate event for a file that was touched but not changed, and the
            // stat-based hasher keeps that for the same price as the stat the watcher already does.
            .fileHasher(FileHasher.LAST_MODIFIED_TIME)
            .listener { event ->
                val p = event.path()
                if (!isIgnored(p)) {
                    runCatching { pending.add(root.relativize(p).toString().replace('\\', '/')) }
                    trigger.trySend(Unit)
                }
            }
            .build()
        watcher!!.watchAsync()
        scope.launch {
            for (signal in trigger) {
                delay(debounceMs) // coalesce a burst of edits into one ingest
                val paths = HashSet(pending).also { pending.removeAll(it) }
                // Path-scoped: only the files that actually changed — engine writes are already
                // committed (cheap no-op), and we never pay a full-tree walk on the write-actor.
                if (paths.isNotEmpty()) applyIngest(engine.ingestExternalChanges(paths))
            }
        }
        return this
    }

    /** Force a debounce-free FULL ingest (used by tests / explicit refresh). */
    suspend fun ingestNow() = applyIngest(engine.ingestExternalChanges())

    private suspend fun applyIngest(commit: String?) {
        if (commit == null) return
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
