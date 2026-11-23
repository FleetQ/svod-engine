package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps the leg P baseline table in docs/retrieval-quality.md equal to what the synthetic corpus
 * actually scores, and checks that the page and the sample golden file describe the real command.
 */
class RetrievalQualityDocsTest {

    private val engineDir: Path = Paths.get(System.getProperty("svod.projectDir") ?: System.getProperty("user.dir"))
    private val repoRoot: Path = engineDir.toAbsolutePath().parent
    private val pagePath: Path = repoRoot.resolve(PAGE)

    private val page: String by lazy {
        assertTrue(Files.exists(pagePath), "$PAGE does not exist")
        Files.readString(pagePath)
    }

    /** `| P/HYBRID | 0.111 | 0.222 | 0.333 | 0.444 | 0.555 |` → label to the five numbers. */
    private fun baselineRows(): Map<String, List<Double>> = page.lines()
        .map { it.trim() }
        .filter { it.startsWith("| P/") }
        .associate { line ->
            val cells = line.trim('|').split('|').map { it.trim() }
            cells[0] to cells.drop(1).take(5).map { it.toDouble() }
        }

    @Test
    fun `the leg P baseline table matches the synthetic corpus`() {
        val rows = baselineRows()
        val modes = listOf(SearchMode.HYBRID, SearchMode.KEYWORD, SearchMode.SEMANTIC)
        assertTrue(rows.keys == modes.map { "P/$it" }.toSet(), "expected rows P/HYBRID, P/KEYWORD, P/SEMANTIC in $PAGE, found ${rows.keys}")

        val fx = IndexFixture.create()
        runBlocking { GoldenCorpus.notes.forEach { (path, content) -> fx.seed(path, content) } }
        fx.use {
            // Leg P as RetrievalEvalTest runs it is not reproducible to 3 decimals. The embedding pass
            // re-writes every note (keyword-only first, then with vectors) from `maxThreads` workers,
            // so Lucene's doc order — which breaks equal BM25 scores — depends on which thread wins;
            // and BM25 counts the deleted copies until a background merge drops them. KEYWORD
            // nDCG@10 measured 0.694 alone and 0.698 next to RetrievalEvalTest. So: one embed worker
            // (fixed re-write order), then close (waits for merges) and reopen. The reopened index is
            // current (same model, every blob indexed) and writes no documents.
            fun open() = IndexService(fx.root, fx.indexDir, BagOfWordsEmbedder(), maxThreads = 1).start()
            open().close()
            open().use { index ->
                val wrong = modes.mapNotNull { mode ->
                    val m = RetrievalEval.run(index, GoldenCorpus.queries, mode)
                    println(m.format("P/$mode"))
                    val measured = listOf(m.recallAt1, m.recallAt5, m.recallAt10, m.ndcgAt10, m.mrr)
                    val documented = rows.getValue("P/$mode")
                    val off = measured.zip(documented).any { (a, b) -> abs(a - b) > TOLERANCE }
                    if (!off) null
                    else "| P/$mode | " + measured.joinToString(" | ") { "%.3f".format(it) } + " | (table says $documented)"
                }
                assertTrue(wrong.isEmpty(), "$PAGE baseline is out of date; leg P measures:\n" + wrong.joinToString("\n"))
            }
        }
    }

    @Test
    fun `the page and the sample golden file describe the real command and format`() {
        assertTrue("svod-engine eval <config.json> <golden.jsonl>" in page, "$PAGE must show the eval command")
        val sample = repoRoot.resolve("examples/golden.sample.jsonl")
        val queries = GoldenSetFile.load(sample)
        assertTrue(queries.size >= 4, "the sample golden file should carry a few example queries")
        assertTrue(queries.map { it.group }.toSet().size >= 2, "the sample golden file should show two '# Section' groups")
    }

    private companion object {
        const val PAGE = "docs/retrieval-quality.md"
        const val TOLERANCE = 0.0015
    }
}
