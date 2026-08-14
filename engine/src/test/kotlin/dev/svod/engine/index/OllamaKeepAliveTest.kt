package dev.svod.engine.index

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ollama unloads a model `5m` after the last request by default, which made the first search of a
 * session pay a full model reload (~6 s measured, vs ~165 ms warm). The embed request must therefore
 * carry an explicit `keep_alive`. Body-shape only — no network.
 */
class OllamaKeepAliveTest {

    @Test
    fun `embed request carries the default keep_alive`() {
        val body = OllamaEmbedder(model = "bge-m3").requestBody(listOf("query: памет"))
        assertTrue(
            body.contains("\"keep_alive\":\"${OllamaEmbedder.DEFAULT_KEEP_ALIVE}\""),
            "expected keep_alive in the embed body, got: $body",
        )
    }

    @Test
    fun `keep_alive is overridable`() {
        val body = OllamaEmbedder(model = "bge-m3", keepAlive = "90s").requestBody(listOf("x"))
        assertTrue(body.contains("\"keep_alive\":\"90s\""), "expected the overridden value, got: $body")
    }

    /**
     * The point of the setting is to outlast Ollama's own `5m` eviction window; a default at or
     * below it would silently reintroduce the reload this change exists to remove. Parses the
     * value instead of restating the constant, so shortening it — or switching to a unit the
     * parser here doesn't recognise — fails.
     */
    @Test
    fun `default keep_alive outlasts ollama's 5m eviction window`() {
        val v = OllamaEmbedder.DEFAULT_KEEP_ALIVE
        val match = Regex("^(\\d+)(s|m|h)$").find(v)
        assertNotNull(match, "keep_alive must be a plain <number><s|m|h> duration, got '$v'")
        val (n, unit) = match.destructured
        val seconds = n.toLong() * when (unit) { "s" -> 1; "m" -> 60; else -> 3600 }
        assertTrue(seconds > 300, "keep_alive '$v' = ${seconds}s does not outlast Ollama's 5m default")
    }

    /**
     * Regression: kotlinx.serialization drops a property that equals its declared default, so
     * `truncate` was documented but never actually put on the wire. Ollama defaults it to true so
     * nothing broke, but the same mechanism would have silently voided `keep_alive`.
     */
    @Test
    fun `embed request carries truncate`() {
        val body = OllamaEmbedder(model = "bge-m3").requestBody(listOf("x"))
        assertTrue(body.contains("\"truncate\":true"), "expected truncate on the wire, got: $body")
    }
}
