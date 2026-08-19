package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.Test

/**
 * Measures what fusion weighting would do, WITHOUT changing the search path.
 *
 * F2 (see docs/architecture-retrieval-quality.md) is that HYBRID scores below its own SEMANTIC leg
 * because `Rrf.fuse` weights both legs equally, so agreement between legs beats strength in one.
 * The sweep pulls each leg's ranking out of the index separately and fuses them here at different
 * weights, so the question "would weighting help, and by how much" is answered by measurement
 * before any production constant moves.
 *
 * Opt-in (`-Dsvod.eval.sweep`): it is an exploration tool, not a gate, and it has no business
 * spending CI time on a question that is already decided.
 */
class FusionWeightSweepTest {

    /**
     * RRF with a per-leg weight, kept HERE rather than in [Rrf]. The sweep below showed weighting
     * does not move the numbers, so production carries no weight parameter for a refuted idea —
     * but the tool that refuted it stays, so the next person can re-run rather than re-argue.
     */
    private fun <T> weightedFuse(rankedLists: List<List<T>>, weights: List<Double>, k: Int = Rrf.DEFAULT_K): List<T> {
        val scores = HashMap<T, Double>()
        rankedLists.forEachIndexed { i, list ->
            val w = weights.getOrElse(i) { 1.0 }
            list.forEachIndexed { rank, id -> scores[id] = (scores[id] ?: 0.0) + w / (k + rank + 1) }
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key }
    }

    @Test
    fun `rerank latency against realistic passage length`() {
        assumeTrue(System.getProperty("svod.eval.sweep") != null, "set -Dsvod.eval.sweep to run the sweep")
        val rrDir = ModelManager.sharedCacheDir().resolve(OnnxLocalReranker.DEFAULT_MODEL)
        assumeTrue(java.nio.file.Files.isRegularFile(rrDir.resolve(ModelManager.MODEL_FILE)), "reranker not cached")

        // The shipped latency gate measured 50 pairs of ~25-token passages and reported 262ms. Real
        // chunks run to 512 tokens, and a live search went from 3.6s to 19.7s once reranking was on.
        // Cost is linear in pairs and roughly linear in tokens, so both knobs get measured here
        // instead of assumed.
        val word = "backup certificate container rotation incident postgres restore alert disk "
        OnnxLocalReranker.load(
            OnnxConfig(modelId = OnnxLocalReranker.DEFAULT_MODEL, localPath = rrDir), Path.of("/unused"),
        ).use { rr ->
            rr.rerank("warmup", listOf(word.repeat(10)))
            // If the tokenizer's truncation options are actually applied, everything past ~512
            // tokens costs nothing and 20000 chars times the same as 2000. If they are NOT, cost
            // grows with the raw text and attention makes it superlinear — which would explain a
            // live search at 19.7s when 50 pairs of 2000 chars measure under two seconds.
            for (chars in listOf(200, 800, 2000, 20000)) {
                for (topK in listOf(10, 20, 50)) {
                    val docs = (1..topK).map { word.repeat(chars / word.length + 1).take(chars) }
                    val t0 = System.nanoTime()
                    rr.rerank("what filled the disk", docs)
                    println("latency chars=%-5d topK=%-3d -> %d ms".format(chars, topK, (System.nanoTime() - t0) / 1_000_000))
                }
            }
        }
    }

    @Test
    fun `does the second stage close F2 - reranked hybrid against each reranked leg`() {
        assumeTrue(System.getProperty("svod.eval.sweep") != null, "set -Dsvod.eval.sweep to run the fusion sweep")
        val config = cachedE5ConfigOrNull()
        assumeTrue(config != null, "multilingual-e5-small not cached")
        val rrDir = Path.of(System.getProperty("user.home"), ".cache", "svod-models", OnnxLocalReranker.DEFAULT_MODEL)
        assumeTrue(java.nio.file.Files.isRegularFile(rrDir.resolve(ModelManager.MODEL_FILE)), "reranker not cached")

        // F2 says HYBRID scores below its own best leg. The shipped configuration now has a second
        // stage on top, so the question that actually matters is whether it still trails AFTER
        // reranking. Comparing reranked hybrid against each reranked leg answers it directly.
        OnnxLocalEmbedder.load(config!!, Path.of("/unused")).use { embedder ->
            OnnxLocalReranker.load(OnnxConfig(modelId = OnnxLocalReranker.DEFAULT_MODEL, localPath = rrDir), Path.of("/unused")).use { rr ->
                val fx = IndexFixture.create()
                runBlocking { GoldenCorpus.notes.forEach { (path, content) -> fx.seed(path, content) } }
                fx.use {
                    fx.newIndex(embedder, rr, "index-rr-sweep").use { index ->
                        for (mode in listOf(SearchMode.HYBRID, SearchMode.KEYWORD, SearchMode.SEMANTIC)) {
                            var nd = 0.0; var r5 = 0.0
                            for (q in GoldenCorpus.queries) {
                                val ranked = RetrievalEval.rankedPaths(index.search(SearchQuery(q.text, mode = mode, limit = 10)).hits)
                                nd += RetrievalEval.ndcgAt(ranked, q, 10); r5 += RetrievalEval.recallAt(ranked, q, 5)
                            }
                            val n = GoldenCorpus.queries.size
                            println("reranked %-9s nDCG@10=%.3f R@5=%.3f".format(mode.name, nd / n, r5 / n))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `how many distinct notes does each mode actually return`() {
        assumeTrue(System.getProperty("svod.eval.sweep") != null, "set -Dsvod.eval.sweep to run the fusion sweep")
        val config = cachedE5ConfigOrNull()
        assumeTrue(config != null, "multilingual-e5-small not cached")

        // Search returns CHUNKS. If several of the ten come from one note, the user sees fewer
        // notes than the limit promises — a recall ceiling that no amount of fusion weighting can
        // lift. This measures that directly, per mode.
        OnnxLocalEmbedder.load(config!!, Path.of("/unused")).use { embedder ->
            val fx = IndexFixture.create()
            runBlocking { GoldenCorpus.notes.forEach { (path, content) -> fx.seed(path, content) } }
            fx.use {
                fx.newIndex(embedder).use { index ->
                    for (mode in listOf(SearchMode.HYBRID, SearchMode.KEYWORD, SearchMode.SEMANTIC)) {
                        var chunks = 0.0; var distinct = 0.0
                        for (q in GoldenCorpus.queries) {
                            val hits = index.search(SearchQuery(q.text, mode = mode, limit = 10)).hits
                            chunks += hits.size
                            distinct += hits.map { it.path }.distinct().size
                        }
                        val n = GoldenCorpus.queries.size
                        println("distinct %-9s chunks/query=%.2f notes/query=%.2f  wasted=%.2f"
                            .format(mode.name, chunks / n, distinct / n, (chunks - distinct) / n))
                    }
                }
            }
        }
    }

    @Test
    fun `sweep semantic leg weight`() {
        assumeTrue(System.getProperty("svod.eval.sweep") != null, "set -Dsvod.eval.sweep to run the fusion sweep")
        val config = cachedE5ConfigOrNull()
        assumeTrue(config != null, "multilingual-e5-small not cached")

        OnnxLocalEmbedder.load(config!!, Path.of("/unused")).use { embedder ->
            val fx = IndexFixture.create()
            runBlocking { GoldenCorpus.notes.forEach { (path, content) -> fx.seed(path, content) } }
            fx.use {
                fx.newIndex(embedder).use { index ->
                    // Production fuses the top 50 candidates per leg, then takes `limit`. Asking each
                    // leg for 50 reproduces exactly those inputs.
                    val legs = GoldenCorpus.queries.map { q ->
                        val kw = index.search(SearchQuery(q.text, mode = SearchMode.KEYWORD, limit = 50)).hits
                        val sem = index.search(SearchQuery(q.text, mode = SearchMode.SEMANTIC, limit = 50)).hits
                        val paths = (kw + sem).associate { it.chunkId to it.path }
                        Triple(q, kw.map { it.chunkId } to sem.map { it.chunkId }, paths)
                    }

                    for (weight in listOf(1.0, 1.25, 1.5, 2.0, 3.0, 5.0)) {
                        var nd = 0.0; var r5 = 0.0; var r1 = 0.0
                        var ndCross = 0.0; var nCross = 0
                        for ((q, ids, paths) in legs) {
                            val (kwIds, semIds) = ids
                            val fused = weightedFuse(listOf(kwIds, semIds), listOf(1.0, weight))
                            val ranked = fused.mapNotNull { paths[it] }.distinct().take(10)
                            nd += RetrievalEval.ndcgAt(ranked, q, 10)
                            r5 += RetrievalEval.recallAt(ranked, q, 5)
                            r1 += RetrievalEval.recallAt(ranked, q, 1)
                            if (q in GoldenCorpus.crossLingualQueries) {
                                ndCross += RetrievalEval.ndcgAt(ranked, q, 10); nCross++
                            }
                        }
                        val n = legs.size
                        println(
                            "sweep semanticWeight=%-5.2f nDCG@10=%.3f R@5=%.3f R@1=%.3f  cross-nDCG@10=%.3f"
                                .format(weight, nd / n, r5 / n, r1 / n, ndCross / nCross),
                        )
                    }
                }
            }
        }
    }
}
