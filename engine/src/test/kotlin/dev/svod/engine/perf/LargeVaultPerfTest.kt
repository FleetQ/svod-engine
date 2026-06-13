package dev.svod.engine.perf

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.index.SearchFilters
import dev.svod.engine.index.SearchMode
import dev.svod.engine.index.SearchQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in scale + soak validation for the Svod engine. NOT part of the normal suite —
 * it is gated on `-Dsvod.perf=true` and skipped otherwise (see [assumeTrue] below).
 *
 * Run:
 *   ./gradlew test --tests "dev.svod.engine.perf.LargeVaultPerfTest" -Dsvod.perf=true
 *
 * It uses [NoneEmbedder] (BM25-only) so there is no model download / ONNX cost — the
 * numbers reflect the engine write path, git commit overhead, and Lucene lexical indexing,
 * which is what we want to characterise at scale.
 */
class LargeVaultPerfTest {

    private val author = Author("perf", "perf@svod.test")

    /** Note count is parameterizable: -Dsvod.perf.notes=N (default 5000). */
    private val noteCount: Int = (System.getProperty("svod.perf.notes") ?: "5000").toInt()

    /** Concurrent writers for the burst that probes write-actor queue depth. */
    private val burstWriters: Int = (System.getProperty("svod.perf.writers") ?: "64").toInt()

    @Test
    fun `large vault scale and soak`() = runBlocking {
        assumeTrue(System.getProperty("svod.perf") == "true") {
            "perf test is opt-in; pass -Dsvod.perf=true to run"
        }

        val root: Path = Files.createTempDirectory("svod-perf-")
        val indexDir = root.resolve(".svod").resolve("index")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SvodEngine.open(root, scope)
        val index = IndexService(root, indexDir, NoneEmbedder).start()
        engine.onCommit { index.onCommit(it) }

        try {
            println()
            println("=== Svod LargeVaultPerfTest (NoneEmbedder / BM25-only) ===")
            println("notes=$noteCount  burstWriters=$burstWriters  vault=$root")

            // ---- 1. bulk write + commit throughput ----------------------------------------
            // Writes are issued from a moderately concurrent pool to keep the actor saturated;
            // the actor serializes them, so this measures end-to-end write+commit throughput.
            val writeStart = System.nanoTime()
            val outcomes = withContext(Dispatchers.Default) {
                (0 until noteCount).chunked(noteCount / 8 + 1).map { batch ->
                    async {
                        batch.map { i ->
                            engine.write(notePath(i), noteBody(i), expectedRevision = null, author = author)
                        }
                    }
                }.awaitAll().flatten()
            }
            val writeNanos = System.nanoTime() - writeStart
            val failures = outcomes.count { it !is WriteOutcome.Success }
            assertEquals(0, failures, "all $noteCount writes must succeed (got $failures failures)")
            val writeSecs = writeNanos / 1e9
            val writeThroughput = noteCount / writeSecs
            println()
            println("[1] BULK WRITE+COMMIT")
            println("    notes written : $noteCount")
            println("    wall time     : ${"%.2f".format(writeSecs)} s")
            println("    throughput    : ${"%.1f".format(writeThroughput)} notes/sec")
            println("    mean per note : ${"%.2f".format(writeSecs * 1000 / noteCount)} ms")

            // ---- 2. index build / catch-up time -------------------------------------------
            // onCommit enqueued an incremental sync per commit; wait for the indexer to drain
            // and reach the expected CHUNK count. docCount()/numDocs() counts Lucene docs =
            // one per markdown chunk, and each generated note has 3 headings (#, ## Related,
            // ## Detail) ⇒ 3 chunks. We time the catch-up tail (waitIdle) plus a bounded poll,
            // so the number reflects "time until the index is consistent with HEAD".
            val chunksPerNote = 3
            val expectedChunks = noteCount * chunksPerNote
            val indexStart = System.nanoTime()
            index.waitIdle()
            // The indexer chases HEAD incrementally; under heavy commit bursts it may still be
            // a few commits behind after a single waitIdle. Poll until consistent.
            var polls = 0
            while (index.docCount() < expectedChunks && polls < 600) {
                index.waitIdle()
                if (index.docCount() < expectedChunks) { Thread.sleep(50); polls++ }
            }
            val indexNanos = System.nanoTime() - indexStart
            val indexSecs = indexNanos / 1e9
            val chunks = index.docCount()
            val indexedFiles = engine.list().count { it.endsWith(".md") }
            println()
            println("[2] INDEX BUILD / CATCH-UP")
            println("    notes (files) : $indexedFiles (target $noteCount)")
            println("    chunks indexed: $chunks (target $expectedChunks, ~$chunksPerNote/note)")
            println("    catch-up time : ${"%.2f".format(indexSecs)} s")
            println("    index rate    : ${"%.1f".format(chunks / indexSecs.coerceAtLeast(1e-6))} chunks/sec")
            assertEquals(noteCount, indexedFiles, "every note file must be present in the vault")
            assertEquals(expectedChunks, chunks, "index must reach the expected chunk count")

            // ---- 3. search latency over ~100 varied queries -------------------------------
            val queries = buildQueries()
            // warm-up (JIT + Lucene reader caches) — excluded from measured latencies.
            repeat(10) { index.search(queries[it % queries.size]) }
            val latenciesMs = ArrayList<Double>(queries.size)
            var totalHits = 0L
            for (q in queries) {
                val t0 = System.nanoTime()
                val r = index.search(q)
                latenciesMs.add((System.nanoTime() - t0) / 1e6)
                totalHits += r.hits.size
            }
            latenciesMs.sort()
            val p50 = percentile(latenciesMs, 50.0)
            val p95 = percentile(latenciesMs, 95.0)
            val p99 = percentile(latenciesMs, 99.0)
            println()
            println("[3] SEARCH LATENCY (${queries.size} queries, hybrid/keyword/filtered mix)")
            println("    p50           : ${"%.2f".format(p50)} ms")
            println("    p95           : ${"%.2f".format(p95)} ms")
            println("    p99           : ${"%.2f".format(p99)} ms")
            println("    max           : ${"%.2f".format(latenciesMs.last())} ms")
            println("    avg hits/query: ${"%.1f".format(totalHits.toDouble() / queries.size)}")

            // ---- 4. memory (used heap before/after, gc first) -----------------------------
            val rt = Runtime.getRuntime()
            System.gc(); Thread.sleep(200); System.gc()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)
            val maxMb = rt.maxMemory() / (1024.0 * 1024.0)
            println()
            println("[4] MEMORY (post-GC, full vault + index resident)")
            println("    used heap     : ${"%.1f".format(usedMb)} MB")
            println("    max heap      : ${"%.1f".format(maxMb)} MB")
            println("    per note      : ${"%.1f".format(usedMb * 1024 / noteCount)} KB")

            // ---- 5. write-actor peak queue depth under a concurrent burst -----------------
            // Fire burstWriters concurrent writes at once; sample queueDepth() while in flight,
            // then read the actor's own peak. This characterises back-pressure under load.
            val burstBase = noteCount
            val sampledPeak = java.util.concurrent.atomic.AtomicInteger(0)
            val sampler = Thread {
                val end = System.nanoTime() + 2_000_000_000L
                while (System.nanoTime() < end) {
                    val d = engine.queueDepth()
                    sampledPeak.updateAndGet { maxOf(it, d) }
                }
            }.apply { isDaemon = true; start() }

            val burstOutcomes = withContext(Dispatchers.Default) {
                (0 until burstWriters).map { i ->
                    async {
                        engine.write(
                            "burst/note-${burstBase + i}.md",
                            noteBody(burstBase + i),
                            expectedRevision = null,
                            author = author,
                        )
                    }
                }.awaitAll()
            }
            sampler.join(2_500)
            val burstFailures = burstOutcomes.count { it !is WriteOutcome.Success }
            assertEquals(0, burstFailures, "all burst writes must succeed")
            val peak = engine.peakQueueDepth()
            println()
            println("[5] WRITE-ACTOR QUEUE DEPTH (burst of $burstWriters concurrent writers)")
            println("    actor peak    : $peak")
            println("    sampled peak  : ${sampledPeak.get()}")
            println("    current depth : ${engine.queueDepth()}")
            println("=== end perf run ===")
            println()

            assertTrue(peak >= 1, "a concurrent burst should produce a peak queue depth >= 1")
        } finally {
            index.close()
            engine.close()
            scope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    // ---- fixture generation -----------------------------------------------------------

    private val tagPool = listOf(
        "animal", "plant", "physics", "history", "code", "music",
        "животные", "история", "музыка", "наука", "проект", "идея",
    )

    private fun notePath(i: Int): String {
        val bucket = i % 50
        return "vault/bucket-$bucket/note-$i.md"
    }

    /**
     * A realistic note: YAML frontmatter with tags + created, a heading, ASCII + Cyrillic
     * prose, and a couple of `[[wikilinks]]` to neighbouring notes. The token `tok$i` makes
     * every note uniquely retrievable; shared vocabulary makes BM25 ranking meaningful.
     */
    private fun noteBody(i: Int): String {
        val t1 = tagPool[i % tagPool.size]
        val t2 = tagPool[(i * 7 + 3) % tagPool.size]
        val created = 1_600_000_000L + i * 60L
        val linkA = i % 1000
        val linkB = (i * 13 + 17) % 1000
        return buildString {
            append("---\n")
            append("tags: [$t1, $t2]\n")
            append("created: $created\n")
            append("---\n")
            append("# Note $i — заметка номер $i\n\n")
            append("This note tok$i discusses $t1 and $t2 at length. ")
            append("Эта заметка описывает тему $t1 и связана с темой $t2. ")
            append("The quick brown fox jumps over the lazy dog while быстрая лиса прыгает. ")
            append("Keywords: alpha beta gamma delta epsilon — альфа бета гамма дельта. ")
            append("Reference number $i with token tok$i appearing again for ranking weight.\n\n")
            append("## Related\n")
            append("See also [[note-$linkA]] and [[note-$linkB]] for context. ")
            append("Смотрите также [[note-$linkA]].\n\n")
            append("## Detail\n")
            append("Paragraph two expands on $t1: lorem ipsum dolor sit amet, consectetur. ")
            append("Параграф два: пример текста на кириллице для проверки токенизации. ")
            append("Final marker tok$i sentinel.\n")
        }
    }

    // ---- query generation -------------------------------------------------------------

    private fun buildQueries(): List<SearchQuery> {
        val qs = ArrayList<SearchQuery>()
        // unique-token lookups (keyword)
        for (k in 0 until 40) {
            qs.add(SearchQuery("tok${k * 113 % noteCount}", mode = SearchMode.KEYWORD, limit = 10))
        }
        // common-vocabulary hybrid queries (large candidate sets — the stressful case)
        val commonTerms = listOf(
            "quick brown fox", "alpha beta gamma", "lorem ipsum dolor",
            "physics history music", "keywords reference number",
            "быстрая лиса прыгает", "альфа бета гамма", "пример текста кириллице",
            "заметка номер тему", "связана темой проект",
        )
        for (term in commonTerms) {
            qs.add(SearchQuery(term, mode = SearchMode.HYBRID, limit = 10))
            qs.add(SearchQuery(term, mode = SearchMode.KEYWORD, limit = 20))
        }
        // filtered queries (tag + path-prefix filters exercise the filter path)
        for (t in tagPool) {
            qs.add(
                SearchQuery(
                    "discusses note",
                    filters = SearchFilters(tags = listOf(t), pathPrefix = "vault/"),
                    mode = SearchMode.KEYWORD,
                    limit = 10,
                ),
            )
        }
        // a few created-range filtered queries
        for (k in 0 until 8) {
            val from = 1_600_000_000L + k * 100_000L
            qs.add(
                SearchQuery(
                    "paragraph expands",
                    filters = SearchFilters(createdFrom = from, createdTo = from + 200_000L),
                    mode = SearchMode.KEYWORD,
                    limit = 10,
                ),
            )
        }
        return qs
    }

    private fun percentile(sortedAsc: List<Double>, p: Double): Double {
        if (sortedAsc.isEmpty()) return 0.0
        val rank = (p / 100.0) * (sortedAsc.size - 1)
        val lo = rank.toInt()
        val hi = (lo + 1).coerceAtMost(sortedAsc.size - 1)
        val frac = rank - lo
        return sortedAsc[lo] + (sortedAsc[hi] - sortedAsc[lo]) * frac
    }
}
