package dev.svod.engine.index

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real in-process cross-encoder reranker (mmarco-mMiniLMv2-L12-H384). Skips cleanly when the
 * model is not cached, like [OnnxEmbedderTest], so CI stays hermetic.
 */
class OnnxRerankerTest {

    private fun configOrSkip(): OnnxConfig {
        val cache = ModelManager.sharedCacheDir().resolve(OnnxLocalReranker.DEFAULT_MODEL)
        val ready = Files.isRegularFile(cache.resolve(ModelManager.MODEL_FILE)) &&
            Files.isRegularFile(cache.resolve(ModelManager.TOKENIZER_FILE))
        assumeTrue(ready, "reranker model not cached at $cache — skipping cross-encoder test")
        return OnnxConfig(modelId = OnnxLocalReranker.DEFAULT_MODEL, localPath = cache)
    }

    @Test
    fun `scores a relevant passage above an irrelevant one, in both languages`() {
        OnnxLocalReranker.load(configOrSkip(), Path.of("/unused")).use { rr ->
            assertEquals(OnnxLocalReranker.PROVIDER, rr.provider)
            assertTrue(rr.isActive)

            val en = rr.rerank(
                "how do I rotate docker container logs",
                listOf(
                    "Set max-size and max-file on the docker daemon's json-file logging driver to rotate container logs.",
                    "Sourdough needs a mature starter and a long cold proof in the fridge overnight.",
                ),
            )
            assertEquals(2, en.size)
            assertTrue(en[0] > en[1], "relevant EN passage scored ${en[0]} vs irrelevant ${en[1]}")

            val bg = rr.rerank(
                "как да подновя сертификата",
                listOf(
                    "Таймер пуска certbot renew два пъти дневно и подновява сертификата, ако остават под тридесет дни.",
                    "Лютеницата се пече на фурна, чушките се белят топли и се смилат с домати.",
                ),
            )
            assertTrue(bg[0] > bg[1], "relevant BG passage scored ${bg[0]} vs irrelevant ${bg[1]}")
        }
    }

    @Test
    fun `cross-lingual pair scores above an unrelated one`() {
        // The capability the eval harness found missing in the bi-encoder (F1). A cross-encoder
        // sees the pair jointly, so this is where a fix could plausibly come from — measured, not
        // assumed.
        OnnxLocalReranker.load(configOrSkip(), Path.of("/unused")).use { rr ->
            val scores = rr.rerank(
                "ядрото уби процеса заради липса на памет",
                listOf(
                    "The kernel ring buffer showed \"Out of memory: Killed process 3121 (java)\" — the largest resident process was terminated.",
                    "The provider rebooted the host for a hypervisor patch and no container came back up.",
                ),
            )
            assertTrue(scores[0] > scores[1], "cross-lingual relevant scored ${scores[0]} vs unrelated ${scores[1]}")
        }
    }

    @Test
    fun `rerank cost for 50 realistic passages stays within its ceiling`() {
        OnnxLocalReranker.load(configOrSkip(), Path.of("/unused")).use { rr ->
            // Realistic length on purpose. The previous version of this test used ~25-token
            // passages, reported 262ms, passed — and live search with reranking on was 20x slower
            // than without. A latency gate fed unrealistic input is worse than no gate: it reports
            // green about a situation that never happens.
            val paragraph = "The host hit one hundred percent on the root filesystem at 02:00 and " +
                "everything went read-only. Disk usage pointed at the container directory: one " +
                "chatty service had written forty-one gigabytes of JSON logs since the last rebuild. "
            val docs = (1..50).map { paragraph.repeat(20).take(OnnxLocalReranker.MAX_PASSAGE_CHARS) }
            rr.rerank("warm up the predictor", docs) // JIT + first-call graph setup

            val start = System.nanoTime()
            val scores = rr.rerank("what filled the disk", docs)
            val millis = (System.nanoTime() - start) / 1_000_000

            assertEquals(50, scores.size)
            println("rerank 50 realistic pairs: ${millis}ms")
            // A REGRESSION ceiling, not a claim of interactivity: ~1.8s measured here is nowhere
            // near interactive, which is exactly why the provider ships disabled. The ceiling is
            // generous because CI hardware varies; it exists to catch a step change in cost.
            assertTrue(millis < COST_CEILING_MS, "50 realistic pairs took ${millis}ms, ceiling is ${COST_CEILING_MS}ms")
        }
    }

    @Test
    fun `concurrent searches get correct scores despite one shared predictor`() {
        // DJL predictors are not thread-safe and rerank() runs inline in search(), so concurrent
        // searches contend on one lock. Interleaved calls must not bleed into each other's results.
        OnnxLocalReranker.load(configOrSkip(), Path.of("/unused")).use { rr ->
            val relevant = "Set max-size and max-file on the docker json-file logging driver to rotate logs."
            val irrelevant = "Лютеницата се пече на фурна и се смила с домати."
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            try {
                val results = (1..40).map {
                    pool.submit<List<Float>> { rr.rerank("how do I rotate docker logs", listOf(relevant, irrelevant)) }
                }.map { it.get() }
                assertTrue(
                    results.all { it.size == 2 && it[0] > it[1] },
                    "a concurrent call returned wrong ordering: ${results.firstOrNull { it[0] <= it[1] }}",
                )
                assertEquals(1, results.map { it[0] }.toSet().size, "the same input scored differently across threads")
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `empty candidate list is a no-op`() {
        OnnxLocalReranker.load(configOrSkip(), Path.of("/unused")).use { rr ->
            assertEquals(emptyList(), rr.rerank("anything", emptyList()))
        }
    }

    private companion object {
        /**
         * Regression ceiling for 50 passages of [OnnxLocalReranker.MAX_PASSAGE_CHARS], measured at
         * ~1.8s on an M-series CPU. Not a latency budget — nothing about 1.8s is interactive, which
         * is why the provider ships disabled. Generous enough to survive CI hardware variance and
         * tight enough to catch the cost doubling.
         */
        const val COST_CEILING_MS = 4000L
    }
}
