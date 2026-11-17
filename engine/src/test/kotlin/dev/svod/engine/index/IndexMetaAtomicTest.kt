package dev.svod.engine.index

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndexMetaAtomicTest {

    /**
     * The embedder swap rewrites the meta file on a background thread while API requests read it
     * (PUT /embedder → indexedDim). A truncate-then-write save let a reader see an empty file and fail
     * with a JsonDecodingException (observed as an intermittent 409 from PUT /embedder).
     */
    @Test
    fun `a reader never sees a half-written meta file while another thread saves it`() {
        val file = Files.createTempDirectory("svod-meta-").resolve("index").resolve("meta.json")
        IndexMeta.save(file, IndexMeta(IndexMeta.SCHEMA_VERSION, "m", 384, "h0"))
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val writer = Thread {
            var i = 0
            while (!stop.get()) IndexMeta.save(file, IndexMeta(IndexMeta.SCHEMA_VERSION, "model-${i % 7}", 384, "head-${i++}"))
        }.apply { start() }
        try {
            repeat(5_000) {
                try { assertEquals(384, IndexMeta.load(file)!!.embeddingDim) } catch (t: Throwable) { failure.compareAndSet(null, t) }
            }
        } finally {
            stop.set(true); writer.join(5_000)
        }
        assertNull(failure.get(), "a load failed during a concurrent save: ${failure.get()}")
        assertEquals(emptyList(), Files.list(file.parent).use { s -> s.map { it.fileName.toString() }.filter { it != "meta.json" }.toList() }, "no temp files left behind")
    }
}
