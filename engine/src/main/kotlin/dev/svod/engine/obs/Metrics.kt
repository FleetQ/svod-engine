package dev.svod.engine.obs

import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight, lock-free metrics for the write path. Index lag, queue depth, and sync status
 * are read live from their owners; this holds the write-latency aggregates.
 */
class Metrics {
    private val writeCount = AtomicLong(0)
    private val writeTotalNanos = AtomicLong(0)
    private val writeMaxNanos = AtomicLong(0)
    @Volatile private var lastNanos = 0L

    fun recordWrite(nanos: Long) {
        writeCount.incrementAndGet()
        writeTotalNanos.addAndGet(nanos)
        lastNanos = nanos
        var prev = writeMaxNanos.get()
        while (nanos > prev && !writeMaxNanos.compareAndSet(prev, nanos)) prev = writeMaxNanos.get()
    }

    fun snapshot(): WriteStats {
        val count = writeCount.get()
        val avg = if (count == 0L) 0.0 else writeTotalNanos.get().toDouble() / count / 1_000_000.0
        return WriteStats(count, round(avg), round(writeMaxNanos.get() / 1_000_000.0), round(lastNanos / 1_000_000.0))
    }

    private fun round(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    data class WriteStats(val count: Long, val avgMs: Double, val maxMs: Double, val lastMs: Double)
}
