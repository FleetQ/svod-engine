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
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.cancellation.CancellationException

/**
 * Watches one [ExternalSource]'s filesystem path and re-syncs it into the vault shortly after edits
 * settle. Built on the native FSEvents-backed [DirectoryWatcher] (low latency, recursive).
 *
 * Design (post-v1.6.2): the **sync worker is decoupled from the watch**. The DirectoryWatcher only
 * feeds a [trigger] channel; a single long-lived worker coroutine drains it with a *trailing*
 * debounce (one sync fires [debounceMs] after the LAST event, so a burst — including an editor's
 * atomic temp+rename — collapses into one sync of the final state). A running sync is **never
 * cancelled**: events arriving while a sync is in flight set a single pending signal (CONFLATED
 * channel), which schedules exactly one follow-up sync afterwards. The watch can die and self-heal
 * (re-arm with backoff) without touching the worker — so a restart can never abort an in-flight sync
 * the way the old watcher-lifecycle-bound scope did ("Job was cancelled").
 *
 * The sync goes through [SourceSync] unchanged (writes via the engine's serialized write-actor), so
 * it can never race an editor/agent write and the external-wins-unless-locally-edited semantics
 * (conflict-preserve, prune soft-delete, secret-scan skip) hold exactly as for a manual sync.
 *
 * A directory source watches its own tree; a single-file source watches the parent directory and
 * filters to that file. [isAlive] reflects whether the underlying watch is currently armed (it may
 * blip false for a moment during self-heal); the owning [SourceWatchManager] no longer tears the
 * whole watcher down on a transient `!isAlive` — only when the source stops being desired.
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

    @Volatile private var watcher: DirectoryWatcher? = null
    @Volatile private var closed = false

    private val target: Path = Paths.get(sourcePath).normalize()
    private val isFile = Files.isRegularFile(target)
    private val watchRoot: Path = if (isFile) target.parent else target

    @Volatile
    var isAlive: Boolean = false
        private set

    /** Begin watching. Returns this on success, or null if the watch could not be armed at all (e.g.
     *  the path vanished between the manager's check and here) — the manager will retry. */
    fun start(): SourceWatcher? {
        if (!arm()) { runCatching { close() }; return null }
        scope.launch { runWorker() } // long-lived; survives watch self-heal, only stops on close()
        return this
    }

    /** Arm (or re-arm) the underlying DirectoryWatcher. Self-heals on completion unless [closed]. */
    private fun arm(): Boolean {
        return try {
            val w = DirectoryWatcher.builder()
                .path(watchRoot)
                .listener { event ->
                    if (relevant(event.path())) {
                        log.debug("fs event for source '{}': {} {}", sourceId, event.eventType(), event.path())
                        trigger.trySend(Unit)
                    }
                }
                .build()
            watcher = w
            isAlive = true
            w.watchAsync().whenComplete { _, ex ->
                isAlive = false
                if (!closed) {
                    log.debug("source watch '{}' ({}) ended ({}) — self-healing", sourceId, watchRoot, ex?.message ?: "completed")
                    scope.launch { rearm() }
                }
            }
            true
        } catch (e: Exception) {
            log.warn("could not arm source watcher for '{}' at {}: {}", sourceId, watchRoot, e.message)
            false
        }
    }

    /** Re-arm after the watch died, with backoff, while the path exists. A successful re-arm queues a
     *  catch-up sync so any change missed during the gap still lands. Does NOT cancel the worker. */
    private suspend fun rearm() {
        var backoff = 200L
        while (scope.isActive && !closed) {
            if (!Files.exists(watchRoot)) { delay(backoff); backoff = (backoff * 2).coerceAtMost(5_000); continue }
            runCatching { watcher?.close() }
            if (arm()) { trigger.trySend(Unit); return } // catch up on anything missed while dead
            delay(backoff); backoff = (backoff * 2).coerceAtMost(5_000)
        }
    }

    /** Drains [trigger] with a trailing debounce: sync fires [debounceMs] after the last event; a new
     *  event during the wait extends the window; an event during a sync schedules exactly one more. */
    private suspend fun runWorker() {
        try {
            while (true) {
                trigger.receive() // block until the first event (throws when the channel closes → exit)
                while (withTimeoutOrNull(debounceMs) { trigger.receive() } != null) { /* extend the quiet window */ }
                syncOnce()
            }
        } catch (e: ClosedReceiveChannelException) {
            // channel closed by close() → worker exits
        } catch (e: CancellationException) {
            // scope cancelled by close() → worker exits
        }
    }

    private suspend fun syncOnce() {
        // Reload the freshest registration each time (config may have changed; gone ⇒ nothing to do).
        val source = store.get(sourceId) ?: return
        log.debug("auto-sync starting for source '{}'", sourceId)
        val r = try {
            SourceSync(engine, store).sync(source)
        } catch (e: CancellationException) {
            throw e // expected only on close(); propagate cancellation, never log it as a failure
        } catch (e: Exception) {
            log.warn("auto-sync of source '{}' failed: {}", sourceId, e.message); return
        }
        log.debug("auto-sync done for source '{}': +{} ~{} !{} -{}", sourceId, r.created.size, r.updated.size, r.conflicts.size, r.deleted.size)
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
        closed = true
        isAlive = false
        runCatching { watcher?.close() }
        trigger.close()
        scope.cancel()
    }
}
