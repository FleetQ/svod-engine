package dev.svod.engine.mcp

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Per-agent token-bucket rate limiter / quota. [capacity] is the burst size; tokens refill
 * at [refillPerSecond]. [tryAcquire] is non-blocking: it returns false when an agent has
 * exhausted its allowance, which the MCP layer maps to a rate-limit error.
 *
 * The clock is injectable so tests can advance time deterministically.
 */
class RateLimiter(
    private val capacity: Double,
    private val refillPerSecond: Double,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Bucket(var tokens: Double, var lastNanos: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val lock = Any()

    fun tryAcquire(agentId: String, permits: Double = 1.0): Boolean = synchronized(lock) {
        val now = nanoTime()
        val b = buckets.getOrPut(agentId) { Bucket(capacity, now) }
        val elapsedSeconds = (now - b.lastNanos) / 1_000_000_000.0
        b.tokens = min(capacity, b.tokens + elapsedSeconds * refillPerSecond)
        b.lastNanos = now
        if (b.tokens >= permits) {
            b.tokens -= permits
            true
        } else {
            false
        }
    }

    companion object {
        /** Generous default: 60 ops burst, refilling 10/s. */
        fun default(): RateLimiter = RateLimiter(capacity = 60.0, refillPerSecond = 10.0)
    }
}
