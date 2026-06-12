package dev.svod.engine.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * THE single writer. All mutating (and, for snapshot consistency, all reading)
 * operations are funneled through one coroutine pinned to one dedicated thread.
 *
 * Tasks are plain blocking lambdas (jgit/filesystem I/O is blocking) executed
 * strictly in submission order. Serialization here is what makes optimistic
 * concurrency sound: between reading the current revision and writing, no other
 * mutation can interleave.
 */
class WriteActor(scope: CoroutineScope) : AutoCloseable {

    private class Task<T>(val block: () -> T, val ack: CompletableDeferred<T>)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "svod-write-actor").apply { isDaemon = false }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val channel = Channel<Task<*>>(Channel.UNLIMITED)

    private val job = scope.launch(dispatcher) {
        for (task in channel) {
            @Suppress("UNCHECKED_CAST")
            val t = task as Task<Any?>
            try {
                t.ack.complete(t.block())
            } catch (e: Throwable) {
                t.ack.completeExceptionally(e)
            }
        }
    }

    /** Submit work to the writer and suspend until it has run. Exceptions propagate. */
    suspend fun <T> submit(block: () -> T): T {
        val ack = CompletableDeferred<T>()
        channel.send(Task(block, ack))
        return ack.await()
    }

    override fun close() {
        channel.close()
        runBlocking { job.join() }
        dispatcher.close()
    }
}
