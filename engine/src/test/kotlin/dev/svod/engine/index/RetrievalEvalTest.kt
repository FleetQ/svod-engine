package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Retrieval-quality evaluation over a golden set. Three legs, three different jobs:
 *
 *  - **P (pipeline)** — synthetic corpus + [FakeEmbedder]. Hermetic, always runs, gates CI.
 *    Proves BM25 + RRF + filters did not regress. It cannot grade semantics: [FakeEmbedder] is a
 *    hashed bag-of-words, so its "similarity" is lexical overlap wearing a vector costume.
 *  - **S (semantic)** — same corpus, real multilingual-e5-small. Skips when the model is not
 *    cached, exactly like [OnnxEmbedderTest]. This is where embedding quality, Bulgarian, and
 *    cross-lingual retrieval are actually graded — and where a reranker must prove itself.
 *  - **V (vault)** — a real vault named by `-Dsvod.eval.vault`, golden set at `-Dsvod.eval.golden`.
 *    Never runs in CI, asserts nothing, prints the true numbers for tuning.
 *
 * Floors are lower bounds measured on a real run, not exact expectations: a floor catches a
 * regression without failing on a harmless reordering. Every leg prints its full metric table, so
 * a passing run still reports where the stack stands.
 */
class RetrievalEvalTest {

    // Floors measured on the first green run (see docs/architecture-retrieval-quality.md A7).
    // Raise them when the stack genuinely improves; never lower one to make a red run green.
    private companion object {
        // Measured 2026-08-19 on multilingual-e5-small, floors = measured - ~0.03.
        const val P_HYBRID_RECALL5 = 0.62      // measured 0.649
        const val P_HYBRID_NDCG10 = 0.53       // measured 0.557
        const val P_KEYWORD_RECALL5 = 0.70     // measured 0.730
        const val S_HYBRID_RECALL5 = 0.78      // measured 0.811
        const val S_HYBRID_NDCG10 = 0.75       // measured 0.786
        // Deliberately low: it pins a KNOWN-BAD number (see the fusion/cross-lingual findings in
        // docs/architecture-retrieval-quality.md) so the gap cannot silently widen. Raise it when
        // cross-lingual retrieval is fixed — that is the point of writing it down.
        const val S_CROSSLINGUAL_RECALL5 = 0.27 // measured 0.300

        /**
         * How far UN-RERANKED HYBRID is allowed to sit below its own best leg (~0.022 today:
         * 0.786 vs SEMANTIC's 0.808).
         *
         * The cause is NOT understood. Two candidates were measured and refuted: per-leg weighting
         * changes nothing up to weight 2.0, and HYBRID actually returns MORE distinct notes per ten
         * chunks than SEMANTIC, not fewer (`FusionWeightSweepTest`). What removes the symptom is the
         * reranker, and the reranked path asserts the strict inequality instead of this tolerance.
         * Kept as a ceiling on a known, unexplained gap so it cannot widen unnoticed.
         */
        const val HYBRID_MAY_TRAIL_BEST_LEG_BY = 0.03
    }

    private fun seeded(embedder: Embedder): Pair<IndexFixture, IndexService> {
        val fx = IndexFixture.create()
        runBlocking { GoldenCorpus.notes.forEach { (path, content) -> fx.seed(path, content) } }
        return fx to fx.newIndex(embedder)
    }

    private fun report(label: String, index: IndexService, queries: List<GoldenQuery>, mode: SearchMode): EvalMetrics {
        val misses = mutableListOf<String>()
        val m = RetrievalEval.run(index, queries, mode) { q, ranked ->
            if (RetrievalEval.recallAt(ranked, q, 5) == 0.0) misses += RetrievalEval.explain(q, ranked)
        }
        println(m.format(label))
        // A miss list is what turns a bad average into a fixable fact — which query, wanting what,
        // getting what instead. Printed even on a pass: a floor can hold while a query rots.
        misses.forEach { println(it) }
        return m
    }

    /** Same measurement, split by the golden set's own groups — an average can hide a dead subset. */
    private fun reportGroups(label: String, index: IndexService, queries: List<GoldenQuery>, mode: SearchMode) {
        queries.filter { it.group.isNotEmpty() }.groupBy { it.group }.forEach { (group, qs) ->
            println("  " + RetrievalEval.run(index, qs, mode).format("$label [$group]"))
        }
    }

    @Test
    fun `golden corpus is internally consistent`() {
        val paths = GoldenCorpus.notes.map { it.first }.toSet()
        assertTrue(GoldenCorpus.notes.size == paths.size, "duplicate note paths in the corpus")

        // A mistyped path is not a loud failure — it silently becomes an unreachable relevant note
        // and depresses recall forever, which is exactly the kind of quiet wrong number this whole
        // harness exists to prevent.
        val unknown = GoldenCorpus.queries.flatMap { q -> q.gains.keys.map { q.text to it } }
            .filter { (_, path) -> path !in paths }
        assertTrue(unknown.isEmpty(), "golden queries reference notes that do not exist: $unknown")

        GoldenCorpus.queries.forEach { q ->
            assertTrue(q.relevant.isNotEmpty(), "query has no relevant note: '${q.text}'")
            assertTrue(q.gains.values.all { it in 0..3 }, "gain outside 0..3 in query: '${q.text}'")
        }

        val subsets = GoldenCorpus.bulgarianQueries + GoldenCorpus.englishQueries + GoldenCorpus.crossLingualQueries
        assertTrue(
            subsets.map { it.text }.toSet() == GoldenCorpus.queries.map { it.text }.toSet(),
            "the three subsets must partition `queries` — otherwise a subset report silently covers a different set",
        )
        assertTrue(GoldenCorpus.crossLingualQueries.size >= 4, "cross-lingual retrieval needs real coverage, not a token case")
    }

    @Test
    fun `leg P - pipeline quality holds on the synthetic corpus`() {
        val (fx, index) = seeded(FakeEmbedder("fake-eval"))
        fx.use {
            index.use {
                val hybrid = report("P/HYBRID", index, GoldenCorpus.queries, SearchMode.HYBRID)
                val keyword = report("P/KEYWORD", index, GoldenCorpus.queries, SearchMode.KEYWORD)
                val semantic = report("P/SEMANTIC", index, GoldenCorpus.queries, SearchMode.SEMANTIC)

                assertTrue(hybrid.recallAt5 >= P_HYBRID_RECALL5, "HYBRID recall@5 ${hybrid.recallAt5} below floor $P_HYBRID_RECALL5")
                assertTrue(hybrid.ndcgAt10 >= P_HYBRID_NDCG10, "HYBRID nDCG@10 ${hybrid.ndcgAt10} below floor $P_HYBRID_NDCG10")
                assertTrue(keyword.recallAt5 >= P_KEYWORD_RECALL5, "KEYWORD recall@5 ${keyword.recallAt5} below floor $P_KEYWORD_RECALL5")
                // Deliberately loose: with a hashed embedder, a tight SEMANTIC floor would be a
                // number about the hash function, not about retrieval quality.
                assertTrue(semantic.recallAt10 > 0.0, "SEMANTIC leg returned nothing — the kNN leg is not firing at all")
            }
        }
    }

    @Test
    fun `leg S - semantic quality on the synthetic corpus with real e5`() {
        val config = cachedE5ConfigOrNull()
        assumeTrue(config != null, "multilingual-e5-small not cached — skipping semantic eval")
        OnnxLocalEmbedder.load(config!!, Path.of("/unused")).use { embedder ->
            val (fx, index) = seeded(embedder)
            fx.use {
                index.use {
                    val hybrid = report("S/HYBRID", index, GoldenCorpus.queries, SearchMode.HYBRID)
                    val keyword = report("S/KEYWORD", index, GoldenCorpus.queries, SearchMode.KEYWORD)
                    val semantic = report("S/SEMANTIC", index, GoldenCorpus.queries, SearchMode.SEMANTIC)

                    report("S/HYBRID bg", index, GoldenCorpus.bulgarianQueries, SearchMode.HYBRID)
                    report("S/HYBRID en", index, GoldenCorpus.englishQueries, SearchMode.HYBRID)
                    val cross = report("S/HYBRID cross-lingual", index, GoldenCorpus.crossLingualQueries, SearchMode.HYBRID)
                    // Split out so a bad cross-lingual number can be attributed: the controls are
                    // literal translations, the rest are also paraphrases carrying distractors.
                    val hardCross = GoldenCorpus.crossLingualQueries - GoldenCorpus.crossLingualControlQueries.toSet()
                    report("S/HYBRID cross-ling hard", index, hardCross, SearchMode.HYBRID)
                    report("S/HYBRID cross-ling control", index, GoldenCorpus.crossLingualControlQueries, SearchMode.HYBRID)
                    report("S/SEMANTIC cross-ling control", index, GoldenCorpus.crossLingualControlQueries, SearchMode.SEMANTIC)

                    assertTrue(hybrid.recallAt5 >= S_HYBRID_RECALL5, "HYBRID recall@5 ${hybrid.recallAt5} below floor $S_HYBRID_RECALL5")
                    assertTrue(hybrid.ndcgAt10 >= S_HYBRID_NDCG10, "HYBRID nDCG@10 ${hybrid.ndcgAt10} below floor $S_HYBRID_NDCG10")
                    // Cross-lingual is the whole reason the embedder is multilingual; nothing else
                    // in the suite would notice if an English-only model were swapped in.
                    assertTrue(cross.recallAt5 >= S_CROSSLINGUAL_RECALL5, "cross-lingual recall@5 ${cross.recallAt5} below floor $S_CROSSLINGUAL_RECALL5")
                    val bestLeg = maxOf(keyword.ndcgAt10, semantic.ndcgAt10)
                    assertTrue(
                        hybrid.ndcgAt10 >= bestLeg - HYBRID_MAY_TRAIL_BEST_LEG_BY,
                        "HYBRID nDCG@10 ${hybrid.ndcgAt10} trails the best single leg $bestLeg by more than " +
                            "$HYBRID_MAY_TRAIL_BEST_LEG_BY — RRF is losing more information than the known gap",
                    )
                }
            }
        }
    }

    @Test
    fun `leg S+R - the reranker must beat plain fusion or it does not ship`() {
        val embedderConfig = cachedE5ConfigOrNull()
        assumeTrue(embedderConfig != null, "multilingual-e5-small not cached — skipping reranker eval")
        val rerankerDir = ModelManager.sharedCacheDir().resolve(OnnxLocalReranker.DEFAULT_MODEL)
        assumeTrue(
            Files.isRegularFile(rerankerDir.resolve(ModelManager.MODEL_FILE)),
            "reranker model not cached — skipping reranker eval",
        )
        val rerankerConfig = OnnxConfig(modelId = OnnxLocalReranker.DEFAULT_MODEL, localPath = rerankerDir)

        OnnxLocalEmbedder.load(embedderConfig!!, Path.of("/unused")).use { embedder ->
            OnnxLocalReranker.load(rerankerConfig, Path.of("/unused")).use { reranker ->
                val fx = IndexFixture.create()
                runBlocking { GoldenCorpus.notes.forEach { (path, content) -> fx.seed(path, content) } }
                fx.use {
                    fx.newIndex(embedder).use { plain ->
                        fx.newIndex(embedder, reranker, "index-reranked").use { reranked ->
                            val before = report("S+R/HYBRID plain", plain, GoldenCorpus.queries, SearchMode.HYBRID)
                            val after = report("S+R/HYBRID reranked", reranked, GoldenCorpus.queries, SearchMode.HYBRID)
                            val crossBefore = report("S+R/cross plain", plain, GoldenCorpus.crossLingualQueries, SearchMode.HYBRID)
                            val crossAfter = report("S+R/cross reranked", reranked, GoldenCorpus.crossLingualQueries, SearchMode.HYBRID)

                            println("S+R delta: nDCG@10 %+.3f, cross-lingual nDCG@10 %+.3f"
                                .format(after.ndcgAt10 - before.ndcgAt10, crossAfter.ndcgAt10 - crossBefore.ndcgAt10))

                            // The ship criterion, stated before the numbers were known: a second
                            // stage that costs a model download and ~260ms per query has to earn
                            // it. If this fails, the reranker does not ship — the eval exists
                            // precisely so that answer can be "no".
                            assertTrue(
                                after.ndcgAt10 > before.ndcgAt10,
                                "reranked nDCG@10 ${after.ndcgAt10} did not beat plain fusion ${before.ndcgAt10}",
                            )

                            // F2 was "HYBRID scores below its own best leg". In the shipped
                            // configuration it no longer does, so this is a strict gate rather than
                            // the tolerance the un-reranked leg still carries. Per-leg weighting was
                            // tried first and measured to do nothing (see FusionWeightSweepTest);
                            // what actually fixed it is the second stage.
                            val legs = listOf(SearchMode.KEYWORD, SearchMode.SEMANTIC).map { mode ->
                                RetrievalEval.run(reranked, GoldenCorpus.queries, mode).ndcgAt10
                            }
                            assertTrue(
                                after.ndcgAt10 >= legs.max() - 1e-9,
                                "reranked HYBRID ${after.ndcgAt10} is below its best reranked leg ${legs.max()} — F2 has regressed",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `leg V diagnostic - where does the right note actually rank`() {
        val vault = System.getProperty("svod.eval.vault")
        val golden = System.getProperty("svod.eval.golden")
        assumeTrue(vault != null && golden != null, "set -Dsvod.eval.vault and -Dsvod.eval.golden")
        val config = cachedE5ConfigOrNull()
        assumeTrue(config != null, "multilingual-e5-small not cached")
        val dir = System.getProperty("svod.eval.indexDir")
        assumeTrue(dir != null, "set -Dsvod.eval.indexDir to an already-built index")

        // Cross-lingual scored a flat 0.000 on the real vault, reranker included. A reranker can
        // only reorder what the first stage retrieved, so the question is whether the right note is
        // just BELOW the candidate window (fixable by widening it) or nowhere in the ranking at all
        // (an embedding problem no window size can fix). This asks the index directly.
        val queries = GoldenSetFile.load(Path.of(golden))
        OnnxLocalEmbedder.load(config!!, Path.of("/unused")).use { embedder ->
            IndexService(Path.of(vault), Path.of(dir), embedder).start().use { index ->
                for (group in queries.mapNotNull { it.group.ifEmpty { null } }.distinct()) {
                    val ranks = queries.filter { it.group == group }.map { q ->
                        val ranked = RetrievalEval.rankedPaths(
                            index.search(SearchQuery(q.text, mode = SearchMode.SEMANTIC, limit = 500)).hits,
                        )
                        q to ranked.indexOfFirst { it in q.relevant }
                    }
                    val found = ranks.filter { it.second >= 0 }
                    println(
                        "diag [%s] n=%d found-in-500=%d median-rank=%s".format(
                            group, ranks.size, found.size,
                            found.map { it.second + 1 }.sorted().let { if (it.isEmpty()) "-" else it[it.size / 2].toString() },
                        ),
                    )
                    ranks.filter { it.second < 0 }.forEach { println("    never in 500: q='${it.first.text}'") }
                }
            }
        }
    }

    @Test
    fun `leg V - real vault eval`() {
        val vault = System.getProperty("svod.eval.vault")
        val golden = System.getProperty("svod.eval.golden")
        assumeTrue(vault != null && golden != null, "set -Dsvod.eval.vault and -Dsvod.eval.golden to run the real-vault eval")
        val config = cachedE5ConfigOrNull()

        val queries = GoldenSetFile.load(Path.of(golden))
        // The vault is READ-ONLY here: IndexService reads through GitReader and the index goes to a
        // temp dir, so SvodEngine never opens and nothing commits into the user's repo.
        // `-Dsvod.eval.embedder=ollama:<model>` measures the embedder the target engine actually
        // runs. Defaulting to the cached e5 once produced a full set of numbers about a model this
        // machine's engine does not use — the metric was right and its subject was wrong.
        // Building the index for a real vault took ~45 minutes. `-Dsvod.eval.indexDir` points at an
        // existing one so a re-run (a new golden set, a different breakdown) costs minutes: the
        // service reconciles against HEAD and re-embeds nothing when the index is already current.
        val indexDir = System.getProperty("svod.eval.indexDir")
            ?.let { Path.of(it) }
            ?: Files.createTempDirectory("svod-eval-index-")
        val embedderId = System.getProperty("svod.eval.embedder")
        println("V/ index dir: $indexDir  embedder: ${embedderId ?: "multilingual-e5-small (default)"}")
        val evalEmbedder: Embedder? = when {
            embedderId != null && embedderId.startsWith("ollama:") ->
                if (OllamaEmbedder.isAvailable()) OllamaEmbedder(embedderId.removePrefix("ollama:"), OllamaEmbedder.DEFAULT_ENDPOINT) else null
            config != null -> OnnxLocalEmbedder.load(config, Path.of("/unused"))
            else -> null
        }
        assumeTrue(evalEmbedder != null, "no eval embedder available (cache multilingual-e5-small or set -Dsvod.eval.embedder=ollama:<model>)")

        (evalEmbedder as? AutoCloseable ?: AutoCloseable {}).use { _ ->
            val embedder = evalEmbedder!!
            IndexService(Path.of(vault), indexDir, embedder).start().use { index ->
                for (mode in listOf(SearchMode.HYBRID, SearchMode.KEYWORD, SearchMode.SEMANTIC)) {
                    report("V/$mode", index, queries, mode)
                }
                reportGroups("V/HYBRID", index, queries, SearchMode.HYBRID)
            }

            // The held-out check that matters: both findings were measured on a 27-note synthetic
            // corpus, and a real vault is the only place to see whether they survive contact with
            // 3000+ notes the corpus author never saw.
            val rerankerDir = ModelManager.sharedCacheDir().resolve(OnnxLocalReranker.DEFAULT_MODEL)
            if (Files.isRegularFile(rerankerDir.resolve(ModelManager.MODEL_FILE))) {
                val rc = OnnxConfig(modelId = OnnxLocalReranker.DEFAULT_MODEL, localPath = rerankerDir)
                OnnxLocalReranker.load(rc, Path.of("/unused")).use { reranker ->
                    // REUSES the index just built (the previous service is closed, so no lock
                    // conflict). Reranking changes only the ordering of results, never the index,
                    // and re-embedding a real vault a second time cost about 45 minutes for
                    // literally identical vectors.
                    IndexService(Path.of(vault), indexDir, embedder, reranker = reranker).start().use { index ->
                        report("V/HYBRID reranked", index, queries, SearchMode.HYBRID)
                        reportGroups("V/HYBRID reranked", index, queries, SearchMode.HYBRID)
                    }
                }
            } else {
                println("V/ reranker model not cached — skipping the reranked comparison")
            }
        }
        // No assertions: floors on private, changing data would fail for reasons unrelated to code.
    }
}
