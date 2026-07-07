package dev.svod.engine.api

import dev.svod.engine.index.estimateTokens
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Idea 1: GET /api/v1/search hits carry a per-hit `tokens` estimate (same estimator as context_pack). */
class SearchTokensTest {

    @Test
    fun `search hits include a non-negative token estimate matching the snippet`() {
        ApiFixture.create().use { fx ->
            runBlocking { fx.engine.write("notes/a.md", "# Alpha\nthe quick brown fox jumps over the lazy dog", null, fx.writeAgent.author) }
            fx.index.waitIdle()

            val resp = fx.get("/api/v1/search?q=${ApiFixture.enc("quick")}")
            assertEquals(200, resp.statusCode())
            val hits = Json.parseToJsonElement(resp.body()).jsonObject["hits"]!!.jsonArray
            assertTrue(hits.isNotEmpty(), "expected a hit")
            val hit = hits[0].jsonObject
            val snippet = hit["snippet"]!!.jsonPrimitive.content
            val tokens = hit["tokens"]!!.jsonPrimitive.content.toInt()
            assertTrue(tokens >= 0, "token estimate is never negative")
            assertEquals(estimateTokens(snippet), tokens, "tokens equal the shared char/4 estimate of the snippet")
        }
    }
}
