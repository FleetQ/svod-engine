package dev.svod.engine.sources

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import io.methvin.watcher.DirectoryWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Watches one [ExternalSource]'s filesystem path and re-syncs it into the vault shortly after edits
 * settle. Built on the native FSEvents-backed [DirectoryWatcher] (low latency, recursive). Events are
 * debounced into a single [SourceSync] run, so a burst of saves — including an editor's atomic
 * temp-file-then-rename — collapses into one sync of the final state. Noisy temp/dot files don't
 * trigger a sync (and [SourceSync] never pulls them into the vault anyway).
 *
 * The sync goes through [SourceSync] unchanged (writes via the engine's serialized write-actor), so
 * it can never race an editor/agent write and the external-wins-unless-locally-edited semantics
 * (conflict-preserve, prune soft-delete, secret-scan skip) hold exactly as for a manual sync.
 *
 * A directory source watches its own tree; a single-file source watches the parent directory and
 * filters to that file. If the watched tree disappears the watcher's future completes and [isAlive]
 * flips to false — the owning [SourceWatchManager] restarts it once the path returns.
 */
class SourceWatcher(
    private val vaultId: String,
    private val engine: SvodEngine,
    private val store: ExternalSourceStore,
    private val eventBus: EventBus,
    private val sourceId: String,
    sourcePath: String,
    private val debounceMs: Long = 250,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(SourceWatcher::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private var watcher: DirectoryWatcher? = null

    private val target: Path = Paths.get(sourcePath).normalize()
    private val isFile = Files.isRegularFile(target)
    private val watchRoot: Path = if (isFile) target.parent else target

    @Volatile
    var isAlive: Boolean = false
        private set

    /** Begin watching. Returns this on success, or null if the path could not be watched (e.g. it
     *  vanished between the manager's check and here) — the manager will retry. */
    fun start(): SourceWatcher? {
        return try {
            val w = DirectoryWatcher.builder()
                .path(watchRoot)
                .listener { event -> if (relevant(event.path())) trigger.trySend(Unit) }
                .build()
            watcher = w
            isAlive = true
            w.watchAsync().whenComplete { _, ex ->
                isAlive = false
                if (ex != null) log.warn("source watcher for '{}' ({}) stopped: {}", sourceId, watchRoot, ex.message)
            }
            scope.launch {
                for (signal in trigger) {
                    delay(debounceMs) // coalesce a burst (incl. atomic save temp+rename) into one sync
                    syncOnce()
                }
            }
            this
        } catch (e: Exception) {
            log.warn("could not start source watcher for '{}' at {}: {}", sourceId, watchRoot, e.message)
            runCatching { close() }
            null
        }
    }

    private suspend fun syncOnce() {
        // Reload the freshest registration each time (config may have changed; gone ⇒ nothing to do).
        val source = store.get(sourceId) ?: return
        val r = try {
            SourceSync(engine, store).sync(source)
        } catch (e: Exception) {
            log.warn("auto-sync of source '{}' failed: {}", sourceId, e.message); return
        }
        eventBus.publish(EventTypes.SOURCE_SYNCED) {
            put("vault", vaultId); put("sourceId", sourceId)
            put("created", r.created.size); put("updated", r.updated.size)
            put("conflicts", r.conflicts.size); put("deleted", r.deleted.size)
        }
    }

    /** A change worth syncing: not a temp/dot file, not inside .git, and (for a file source) the target. */
    private fun relevant(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        if (isFile) return path.normalize() == target
        if (name.startsWith(".") || name.endsWith("~") || name.endsWith(".swp") || name.endsWith(".swx") || name.endsWith(".tmp")) return false
        return runCatching { watchRoot.relativize(path).none { it.toString() == ".git" } }.getOrDefault(true)
    }

    override fun close() {
        isAlive = false
        runCatching { watcher?.close() }
        trigger.close()
        scope.cancel()
    }
}
