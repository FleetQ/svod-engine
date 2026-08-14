package dev.svod.engine.index

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CachingEmbedder] must cut repeat query embeds without changing any observable behaviour.
 * [FakeEmbedder] counts delegate calls, which is what makes "did it hit the cache" assertable.
 */
class CachingEmbedderTest {

    @Test
    fun `repeated query embeds once`() {
        val fake = FakeEmbedder("fake")
        val cached = CachingEmbedder(fake)

        val first = cached.embedQuery("памет")
        val second = cached.embedQuery("памет")

        assertEquals(1, fake.queryCalls.get(), "second identical query must be served from cache")
        assertContentEquals(first, second)
    }

    @Test
    fun `distinct queries each embed`() {
        val fake = FakeEmbedder("fake")
        val cached = CachingEmbedder(fake)

        cached.embedQuery("първи")
        cached.embedQuery("втори")

        assertEquals(2, fake.queryCalls.get())
        assertEquals(2, cached.size())
    }

    @Test
    fun `passages are never cached`() {
        val fake = FakeEmbedder("fake")
        val cached = CachingEmbedder(fake)

        cached.embedPassages(listOf("same text"))
        cached.embedPassages(listOf("same text"))

        assertEquals(2, fake.passageCalls.get(), "chunk texts are unique; caching them would only waste heap")
        assertEquals(0, cached.size())
    }

    @Test
    fun `mutating a returned vector does not corrupt the cache`() {
        val fake = FakeEmbedder("fake")
        val cached = CachingEmbedder(fake)

        val pristine = cached.embedQuery("заявка").copyOf()
        val handedOut = cached.embedQuery("заявка")
        handedOut[0] = 999f

        assertContentEquals(pristine, cached.embedQuery("заявка"), "cache must hand out copies, not the live array")
    }

    @Test
    fun `least recently used entry is evicted at capacity`() {
        val fake = FakeEmbedder("fake")
        val cached = CachingEmbedder(fake, capacity = 2)

        cached.embedQuery("a")
        cached.embedQuery("b")
        cached.embedQuery("a") // refresh "a" so "b" becomes the eldest
        cached.embedQuery("c") // evicts "b"
        assertEquals(3, fake.queryCalls.get())
        assertEquals(2, cached.size())

        cached.embedQuery("a")
        assertEquals(3, fake.queryCalls.get(), "'a' was refreshed, so it must still be cached")

        cached.embedQuery("b")
        assertEquals(4, fake.queryCalls.get(), "'b' was evicted, so it must be re-embedded")
    }

    @Test
    fun `delegates identity and dimension`() {
        val fake = FakeEmbedder("bge-m3", dim = 128)
        val cached = CachingEmbedder(fake)

        assertEquals("bge-m3", cached.model)
        assertEquals(128, cached.dim)
        assertEquals(128, cached.knownDim())
        assertTrue(cached.isActive)
    }

    @Test
    fun `inactive delegate stays inactive`() {
        val cached = CachingEmbedder(FakeEmbedder("fake", dim = 0))
        assertFalse(cached.isActive, "isActive must reflect the delegate, not the wrapper")
    }

    /**
     * Regression: a provider swap disposes the previous embedder via `(previous as? AutoCloseable)`
     * (`IndexService.setEmbedder`, `EmbedderController`). A wrapper that is not itself AutoCloseable
     * makes that cast fail silently and strands `OnnxLocalEmbedder`'s native ONNX session.
     */
    @Test
    fun `close propagates to an AutoCloseable delegate`() {
        class ClosableEmbedder : Embedder, AutoCloseable {
            override val model = "closable"
            override val dim = 4
            var closed = false
            override fun embedPassages(texts: List<String>) = texts.map { FloatArray(4) }
            override fun embedQuery(text: String) = FloatArray(4)
            override fun close() { closed = true }
        }

        val inner = ClosableEmbedder()
        val cached = CachingEmbedder(inner)
        cached.embedQuery("warm the cache")

        (cached as Embedder as? AutoCloseable)?.close()

        assertTrue(inner.closed, "the delegate must be closed through the wrapper")
        assertEquals(0, cached.size(), "close must drop cached vectors")
    }

    /** Smoke test: asserts only that closing a non-AutoCloseable delegate does not throw. */
    @Test
    fun `close is safe when the delegate is not closeable`() {
        CachingEmbedder(FakeEmbedder("fake")).close()
    }

    @Test
    fun `delegate is reachable for provider type checks`() {
        val fake = FakeEmbedder("fake")
        assertTrue(CachingEmbedder(fake).delegate === fake)
    }

    @Test
    fun `a failing embed is not cached`() {
        var fail = true
        val flaky = object : Embedder {
            override val model = "flaky"
            override val dim = 8
            var calls = 0
            override fun embedPassages(texts: List<String>) = error("unused")
            override fun embedQuery(text: String): FloatArray {
                calls++
                if (fail) throw IllegalStateException("ollama down")
                return FloatArray(8) { 1f }
            }
        }
        val cached = CachingEmbedder(flaky)

        assertFailsWith<IllegalStateException> { cached.embedQuery("q") }
        assertEquals(0, cached.size(), "a failed embed must leave nothing behind")

        fail = false
        assertContentEquals(FloatArray(8) { 1f }, cached.embedQuery("q"), "a retry after recovery must succeed")
        assertEquals(2, flaky.calls)
    }
}
