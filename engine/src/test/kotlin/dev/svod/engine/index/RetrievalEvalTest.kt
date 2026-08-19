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
         * How far HYBRID is currently allowed to sit below its own best leg.
         *
         * It should be zero: fusion that loses to one of its inputs is not earning its complexity.
         * Measured today it is ~0.022 below SEMANTIC (0.786 vs 0.808), because plain RRF weights a
         * weaker keyword leg equally. Pinned as a ceiling on the KNOWN gap rather than asserted
         * away — this catches further degradation while the real fix (leg weighting) is open work.
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
    fun `leg V - real vault eval`() {
        val vault = System.getProperty("svod.eval.vault")
        val golden = System.getProperty("svod.eval.golden")
        assumeTrue(vault != null && golden != null, "set -Dsvod.eval.vault and -Dsvod.eval.golden to run the real-vault eval")
        val config = cachedE5ConfigOrNull()
        assumeTrue(config != null, "multilingual-e5-small not cached — skipping vault eval")

        val queries = GoldenSetFile.load(Path.of(golden))
        // The vault is READ-ONLY here: IndexService reads through GitReader and the index goes to a
        // temp dir, so SvodEngine never opens and nothing commits into the user's repo.
        val indexDir = Files.createTempDirectory("svod-eval-index-")
        OnnxLocalEmbedder.load(config!!, Path.of("/unused")).use { embedder ->
            IndexService(Path.of(vault), indexDir, embedder).start().use { index ->
                for (mode in listOf(SearchMode.HYBRID, SearchMode.KEYWORD, SearchMode.SEMANTIC)) {
                    report("V/$mode", index, queries, mode)
                }
            }
        }
        // No assertions: floors on private, changing data would fail for reasons unrelated to code.
    }
}
