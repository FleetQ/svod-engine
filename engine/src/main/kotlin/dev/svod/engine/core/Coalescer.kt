package dev.svod.engine.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Per-key trigger discipline for background work fired by vault commits (two-way sync, on-change
 * backup, source write-back): debounce the trigger, never the work.
 *
 *  - [changed] (re)arms a [quietMillis] delay, so a burst of commits yields one run. The first change
 *    of a batch also arms a [maxWaitMillis] cap, so a key that never goes quiet still runs that often.
 *  - At most one run per key. A change while it runs only marks the key dirty; when the run ends,
 *    exactly ONE trailing run is debounced — changes coalesce, they do not queue.
 *  - [runNow] starts a run at once, or joins the one in progress (a pending delay is dropped: the run
 *    it starts covers those changes).
 *  - Only [stop] cancels a run. A new change never does: on a large vault one run outlasts the gap
 *    between writes, and cancelling it restarted the work over and over (engine 1.25.4).
 */
class Coalescer<R>(
    private val scope: CoroutineScope,
    private val quietMillis: Long,
    private val maxWaitMillis: Long = Long.MAX_VALUE,
    private val work: suspend (key: String, trigger: String) -> R,
) {
    private val quiet = HashMap<String, Job>()        // key → pending quiet-period delay
    private val deadline = HashMap<String, Job>()     // key → max-wait cap of the pending batch
    private val running = HashMap<String, Deferred<R>>()
    private val dirty = HashSet<String>()             // changed while its run was in progress
    private var stopped = false

    /** A change for [key]: debounce a run, or mark it dirty for one trailing run if one is in progress. */
    fun changed(key: String): Unit = synchronized(this) {
        if (stopped) return
        if (key in running) dirty += key else arm(key)
    }

    /** Start a run for [key] now, or join the one already in progress. */
    fun runNow(key: String, trigger: String): Deferred<R> = synchronized(this) {
        if (stopped) return CompletableDeferred<R>().apply { cancel() }
        running[key] ?: start(key, trigger)
    }

    fun isRunning(key: String): Boolean = synchronized(this) { key in running }

    /** A run for [key] is scheduled but not started (a delay is armed, or it changed mid-run). */
    fun isPending(key: String): Boolean = synchronized(this) { key in quiet || key in dirty }

    /** Drop [key] entirely (its vault was removed): cancel its pending delay and its run in progress. */
    fun cancel(key: String): Unit = synchronized(this) {
        listOfNotNull(quiet.remove(key), deadline.remove(key), running.remove(key)).forEach { it.cancel() }
        dirty -= key
    }

    /** Engine shutdown: cancel every pending delay AND every run in progress. */
    fun stop(): Unit = synchronized(this) {
        stopped = true
        (quiet.values + deadline.values + running.values).forEach { it.cancel() }
        quiet.clear(); deadline.clear(); running.clear(); dirty.clear()
    }

    /** Caller holds the monitor. */
    private fun arm(key: String) {
        quiet.remove(key)?.cancel()
        quiet[key] = timer(key, quietMillis)
        if (maxWaitMillis != Long.MAX_VALUE && key !in deadline) deadline[key] = timer(key, maxWaitMillis)
    }

    private fun timer(key: String, millis: Long): Job = scope.launch {
        delay(millis)
        val me = coroutineContext[Job]
        synchronized(this@Coalescer) {
            if (quiet[key] !== me && deadline[key] !== me) return@launch // superseded or already started
            if (key !in running) start(key, "on-change")
        }
    }

    /** Caller holds the monitor. */
    private fun start(key: String, trigger: String): Deferred<R> {
        quiet.remove(key)?.cancel()
        deadline.remove(key)?.cancel()
        val run = scope.async(start = CoroutineStart.LAZY) {
            try {
                work(key, trigger)
            } finally {
                synchronized(this@Coalescer) {
                    if (running[key] === coroutineContext[Job]) running.remove(key)
                    if (dirty.remove(key) && !stopped) arm(key)
                }
            }
        }
        running[key] = run
        run.start()
        return run
    }
}
