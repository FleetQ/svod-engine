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
    fun `a remote endpoint with a trailing slash does not produce a doubled path`() {
        // Endpoints are typed by hand into a config file, so a trailing slash is a matter of time.
        // OpenAiEmbedder already trims; RemoteReranker did not, and `host//rerank` 404s on TEI.
        val calls = mutableListOf<String>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            calls += ex.requestURI.path
            val body = """[{"index":0,"score":0.9}]""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/"
            RemoteReranker("m", base).rerank("q", listOf("d"))
            assertEquals(listOf("/rerank"), calls, "endpoint path was doubled by the trailing slash")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `none and remote still resolve as before`() {
        val vault = Files.createTempDirectory("svod-rerank-wiring-")
        assertEquals(NoneReranker, Rerankers.create(RerankerConfig(), vault))
        val remote = Rerankers.create(RerankerConfig(provider = RerankerProvider.REMOTE), vault)
        assertEquals("remote", remote.provider)
        assertTrue(remote.isActive)
    }

    @Test
    fun `a rerank larger than the server's batch cap is split, and the order is preserved`() {
        // The defect this guards: TEI caps a request at 32 texts and answers HTTP 413 above it,
        // while rerankTopK is 50. Every call failed, maybeRerank swallowed it and returned the
        // fused order, and the eval printed a full set of "reranking did not help" numbers that
        // were really "reranking never ran".
        //
        // The server here REJECTS an oversized batch exactly as TEI does, so this test fails
        // against the unbatched client rather than merely observing the batched one.
        val batchSizes = mutableListOf<Int>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val n = Regex("\"texts\"\\s*:\\s*\\[(.*)]", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.count { it == '"' }?.div(2) ?: 0
            batchSizes += n
            val payload = if (n > 32) {
                ex.sendResponseHeaders(413, 0); ex.responseBody.use { }; return@createContext
            } else {
                // Score descending by index so the caller cannot accidentally look correct by
                // returning the input order.
                (0 until n).joinToString(",", "[", "]") { """{"index":$it,"score":${1.0 - it / 100.0}}""" }
            }
            val bytes = payload.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}"
            val docs = (1..50).map { "document number $it" }
            val out = RemoteReranker("m", endpoint).rerank("q", docs)

            assertEquals(listOf(32, 18), batchSizes, "50 documents must go out as 32 + 18, never as one 50")
            assertEquals(50, out.size, "every document must get a score, across both batches")
            // Scores come back positionally aligned with `docs`. The stub scores each batch from
            // 1.0 downward, so the second batch restarting at 1.0 is what proves the results were
            // concatenated in order rather than sorted, overwritten, or silently truncated.
            assertEquals(1.0f, out[0], "first document of the first batch")
            assertEquals(1.0f - 31 / 100.0f, out[31], "last document of the first batch")
            assertEquals(1.0f, out[32], "the second batch must start a new score sequence here")
            assertEquals(1.0f - 17 / 100.0f, out[49], "last document of the second batch")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a batch at exactly the cap is sent as one request`() {
        // Off-by-one guard: chunking at <= maxBatch would send 32 as 32+0 or split needlessly.
        val batchSizes = mutableListOf<Int>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val n = Regex("\"texts\"\\s*:\\s*\\[(.*)]", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.count { it == '"' }?.div(2) ?: 0
            batchSizes += n
            val payload = (0 until n).joinToString(",", "[", "]") { """{"index":$it,"score":0.5}""" }
            val bytes = payload.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}"
            RemoteReranker("m", endpoint).rerank("q", (1..32).map { "doc $it" })
            assertEquals(listOf(32), batchSizes, "exactly the cap must be one request")
        } finally {
            server.stop(0)
        }
    }
}
