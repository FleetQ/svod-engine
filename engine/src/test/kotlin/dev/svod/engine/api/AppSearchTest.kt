package dev.svod.engine.api

import dev.svod.engine.core.Author
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /api/v1/search: filter-only "browse by tag" (no q) matches the /api/v1/tags counts exactly. */
class AppSearchTest {

    private val UI = Author("ui", "ui@svod.local")
    private fun paths(body: String): Set<String> =
        Json.parseToJsonElement(body).jsonObject["hits"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }.toSet()

    @Test
    fun `filter-only tag browse returns exactly the tagged notes and the tag count matches`() = runBlocking {
        ApiFixture.create().use { fx ->
            fx.engine.write("a.md", "---\ntags: [laravel]\n---\n# A\nbody", null, UI)
            fx.engine.write("b.md", "---\ntags: [laravel]\n---\n# B\nbody", null, UI)
            fx.engine.write("c.md", "---\ntags: [daily]\n---\n# C\nbody", null, UI)
            fx.engine.write("skill.md", "# Skill\nlaravel appears in the body but is not a tag", null, UI)
            fx.index.waitIdle()

            // browse by tag with NO query → exactly the two laravel-tagged notes (NOT skill.md)
            val laravel = fx.get("/api/v1/search?tags=laravel&limit=50")
            assertEquals(200, laravel.statusCode())
            assertEquals(setOf("a.md", "b.md"), paths(laravel.body()), laravel.body())

            // q=* + tag is also exact
            val star = fx.get("/api/v1/search?q=*&tags=laravel&limit=50")
            assertEquals(setOf("a.md", "b.md"), paths(star.body()))

            // daily → exactly its one note; and it equals the /tags count for daily
            val daily = fx.get("/api/v1/search?tags=daily&limit=50")
            assertEquals(setOf("c.md"), paths(daily.body()))
            val tagsBody = fx.get("/api/v1/tags").body()
            val dailyCount = Json.parseToJsonElement(tagsBody).jsonObject["tags"]!!.jsonArray
                .first { it.jsonObject["tag"]!!.jsonPrimitive.content == "daily" }.jsonObject["count"]!!.jsonPrimitive.content.toInt()
            assertEquals(dailyCount, paths(daily.body()).size, "filter-only result matches the /tags count")

            // neither q nor a filter → 400
            assertEquals(400, fx.get("/api/v1/search").statusCode())
            // a plain text query still works
            assertTrue(fx.get("/api/v1/search?q=body").statusCode() == 200)
        }
    }
}
