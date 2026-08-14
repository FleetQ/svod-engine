package dev.svod.engine.index

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [LuceneIndex.pathsMissingVectors] drives resume-after-interrupt for the background embedder, so
 * its result has to stay exact after the switch from scanning every stored `vecBytes` to negating
 * a [org.apache.lucene.search.FieldExistsQuery] over the kNN field.
 */
class MissingVectorsTest {

    private fun chunk(text: String) = Chunk(0, "heading", text)

    private fun withIndex(block: (LuceneIndex) -> Unit) {
        val dir: Path = Files.createTempDirectory("svod-missing-vec-")
        try {
            LuceneIndex(dir.resolve("index")).use(block)
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `empty index has no backlog`() = withIndex { idx ->
        assertEquals(emptyList(), idx.pathsMissingVectors())
    }

    @Test
    fun `every path is backlog when nothing is embedded`() = withIndex { idx ->
        idx.upsertFile("a.md", "blob-a", emptyList(), null, listOf(LuceneIndex.ChunkDoc(chunk("alpha"), null)))
        idx.upsertFile("b.md", "blob-b", emptyList(), null, listOf(LuceneIndex.ChunkDoc(chunk("beta"), null)))
        idx.commit()

        assertEquals(listOf("a.md", "b.md"), idx.pathsMissingVectors().sorted())
    }

    @Test
    fun `fully embedded index has no backlog`() = withIndex { idx ->
        val vec = FloatArray(4) { 0.5f }
        idx.upsertFile("a.md", "blob-a", emptyList(), null, listOf(LuceneIndex.ChunkDoc(chunk("alpha"), vec)))
        idx.upsertFile("b.md", "blob-b", emptyList(), null, listOf(LuceneIndex.ChunkDoc(chunk("beta"), vec)))
        idx.commit()

        assertEquals(emptyList(), idx.pathsMissingVectors())
    }

    @Test
    fun `only unembedded paths are reported`() = withIndex { idx ->
        val vec = FloatArray(4) { 0.5f }
        idx.upsertFile("done.md", "blob-1", emptyList(), null, listOf(LuceneIndex.ChunkDoc(chunk("alpha"), vec)))
        idx.upsertFile("todo.md", "blob-2", emptyList(), null, listOf(LuceneIndex.ChunkDoc(chunk("beta"), null)))
        idx.commit()

        assertEquals(listOf("todo.md"), idx.pathsMissingVectors())
    }

    @Test
    fun `a path with any unembedded chunk is backlog`() = withIndex { idx ->
        val vec = FloatArray(4) { 0.5f }
        idx.upsertFile(
            "mixed.md", "blob-3", emptyList(), null,
            listOf(
                LuceneIndex.ChunkDoc(Chunk(0, "h1", "embedded"), vec),
                LuceneIndex.ChunkDoc(Chunk(1, "h2", "not embedded"), null),
            ),
        )
        idx.commit()

        assertEquals(listOf("mixed.md"), idx.pathsMissingVectors(), "a partially embedded note still needs work")
    }

    @Test
    fun `embedding a backlog path clears it`() = withIndex { idx ->
        idx.upsertFile("a.md", "blob-a", emptyList(), null, listOf(LuceneIndex.ChunkDoc(chunk("alpha"), null)))
        idx.commit()
        assertEquals(listOf("a.md"), idx.pathsMissingVectors())

        idx.upsertFile("a.md", "blob-a", emptyList(), null, listOf(LuceneIndex.ChunkDoc(chunk("alpha"), FloatArray(4) { 0.5f })))
        idx.commit()
        assertEquals(emptyList(), idx.pathsMissingVectors())
    }
}
