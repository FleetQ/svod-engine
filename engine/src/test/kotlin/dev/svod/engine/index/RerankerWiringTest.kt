package dev.svod.engine.index

import dev.svod.engine.lifecycle.SvodConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Config → provider wiring for reranking. The provider name lives in three places that can drift
 * independently — the [RerankerProvider] enum, [SvodConfig.RERANKER_PROVIDERS] validation, and the
 * mapping in `toRerankerConfig` — and a value present in one but missing from another fails in a
 * quiet way: the config validates and then silently resolves to `none`, so search just stays
 * un-reranked with nothing in the log to explain it.
 */
class RerankerWiringTest {

    @Test
    fun `every provider enum value is reachable from an accepted config string`() {
        val reachable = SvodConfig.RERANKER_PROVIDERS
            .map { SvodConfig(reranker = SvodConfig.RerankerSettings(provider = it)).toRerankerConfig().provider }
            .toSet()
        assertEquals(
            RerankerProvider.entries.toSet(),
            reachable,
            "a RerankerProvider value has no accepted config string, so it can never be selected",
        )
    }

    @Test
    fun `local-onnx validates and maps, under both spellings`() {
        for (spelling in listOf("local-onnx", "onnx-local")) {
            val config = SvodConfig(reranker = SvodConfig.RerankerSettings(provider = spelling))
            // Only the reranker field is under test here; an empty SvodConfig has unrelated errors
            // (no vault configured), and asserting on all of them would fail for the wrong reason.
            val errors = config.validate().filter { it.contains("reranker.provider") }
            assertTrue(errors.isEmpty(), "provider '$spelling' should be accepted, got $errors")
            val rc = config.toRerankerConfig()
            assertEquals(RerankerProvider.LOCAL_ONNX, rc.provider, "spelling '$spelling'")
            assertEquals(OnnxLocalReranker.DEFAULT_MODEL, rc.onnx.modelId)
        }
    }

    @Test
    fun `a remote model name does not leak into the local provider`() {
        // Switching provider without clearing `model` used to be enough to hand a local loader an
        // endpoint's model name, which ModelManager has no pin for.
        val rc = SvodConfig(reranker = SvodConfig.RerankerSettings(provider = "local-onnx")).toRerankerConfig()
        assertEquals(OnnxLocalReranker.DEFAULT_MODEL, rc.model)
        assertEquals(rc.model, rc.onnx.modelId)
    }

    @Test
    fun `an unknown provider is rejected by validation`() {
        val errors = SvodConfig(reranker = SvodConfig.RerankerSettings(provider = "magic")).validate()
        assertTrue(errors.any { it.contains("reranker.provider") }, "expected a reranker.provider error, got $errors")
    }

    @Test
    fun `a local model that cannot load never becomes active, and never blocks the caller`() {
        // Ranking is an optimisation; search staying up is not. An unknown model id has no pin, so
        // ModelManager cannot resolve it — the vault must still open, promptly.
        // Asserted on BEHAVIOUR, not identity: loading is asynchronous, so `create` returns a
        // pending reranker and it is `isActive` that the search path consults.
        val vault = Files.createTempDirectory("svod-rerank-wiring-")
        val config = RerankerConfig(
            provider = RerankerProvider.LOCAL_ONNX,
            onnx = OnnxConfig(modelId = "no-such-model-anywhere"),
        )
        val start = System.nanoTime()
        val reranker = Rerankers.create(config, vault)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 1_000, "create() blocked for ${elapsedMs}ms — model loading must not be on the caller's thread")

        // Give the background loader time to fail, then confirm it stays inactive rather than
        // flipping active or throwing into a search.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !reranker.isActive) Thread.sleep(25)
        assertTrue(!reranker.isActive, "a reranker whose model cannot load must never report itself active")
        (reranker as? AutoCloseable)?.close()
    }

    @Test
    fun `none and remote still resolve as before`() {
        val vault = Files.createTempDirectory("svod-rerank-wiring-")
        assertEquals(NoneReranker, Rerankers.create(RerankerConfig(), vault))
        val remote = Rerankers.create(RerankerConfig(provider = RerankerProvider.REMOTE), vault)
        assertEquals("remote", remote.provider)
        assertTrue(remote.isActive)
    }
}
