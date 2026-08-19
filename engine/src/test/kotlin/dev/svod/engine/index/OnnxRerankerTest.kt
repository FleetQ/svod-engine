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
    fun `reranks 50 pairs within the interactive latency budget`() {
        OnnxLocalReranker.load(configOrSkip(), Path.of("/unused")).use { rr ->
            val docs = (1..50).map {
                "Note $it: a paragraph of ordinary operational prose about backups, certificates, " +
                    "containers and the various ways an evening can be ruined by a full disk."
            }
            rr.rerank("warm up the predictor", docs) // JIT + first-call graph setup

            val start = System.nanoTime()
            val scores = rr.rerank("what filled the disk", docs)
            val millis = (System.nanoTime() - start) / 1_000_000

            assertEquals(50, scores.size)
            println("rerank 50 pairs: ${millis}ms")
            // The budget the model was chosen against. This is a real gate: a reranker that misses
            // it does not belong on an interactive search path, however good its ranking is.
            assertTrue(millis < LATENCY_BUDGET_MS, "50 pairs took ${millis}ms, budget is ${LATENCY_BUDGET_MS}ms")
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
        const val LATENCY_BUDGET_MS = 300L
    }
}
