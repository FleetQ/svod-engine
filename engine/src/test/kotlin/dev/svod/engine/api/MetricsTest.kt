package dev.svod.engine.api

import dev.svod.engine.core.Author
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTest {

    @Test
    fun `metrics reflect write activity and index lag`() = runBlocking {
        ApiFixture.create().use { fx ->
            repeat(5) { i -> fx.engine.write("m/$i.md", "# $i\nbody", expectedRevision = null, author = Author("ui", "ui@svod.local")) }
            fx.index.waitIdle()

            val body = fx.get("/api/v1/metrics").body()
            val json = Json.parseToJsonElement(body).jsonObject

            val writeCount = json["write"]!!.jsonObject["count"]!!.jsonPrimitive.content.toLong()
            assertTrue(writeCount >= 5, "write count should reflect the writes: $writeCount")

            val index = json["index"]!!.jsonObject
            assertEquals("false", index["lagging"]!!.jsonPrimitive.content, "index caught up after waitIdle")
            assertTrue(index["docCount"]!!.jsonPrimitive.content.toInt() >= 5)

            assertEquals("0", json["conflicts"]!!.jsonPrimitive.content)
            assertTrue(json["peakQueueDepth"]!!.jsonPrimitive.content.toInt() >= 1)
        }
    }
}
