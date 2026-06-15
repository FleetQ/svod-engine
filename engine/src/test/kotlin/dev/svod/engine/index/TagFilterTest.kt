package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tag filtering is an exact AND filter, and a filter-only query (blank text) browses by tag. */
class TagFilterTest {

    private fun paths(r: SearchResult) = r.hits.map { it.path }.toSet()

    @Test
    fun `tag filter is exact, and a filter-only query returns exactly the tagged notes`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                fx.seed("a.md", "---\ntags: [laravel]\n---\n# A\ncommon body alpha")
                fx.seed("b.md", "---\ntags: [laravel]\n---\n# B\ncommon body beta")
                fx.seed("c.md", "---\ntags: [php]\n---\n# C\ncommon body gamma")
                fx.seed("d.md", "# D\ncommon body delta no tags")
            }
            fx.newIndex(FakeEmbedder("f")).use { idx ->
                fun q(text: String, tag: String?, mode: SearchMode) =
                    idx.search(SearchQuery(text, if (tag == null) SearchFilters() else SearchFilters(tags = listOf(tag)), mode, limit = 50))

                // exact AND with a query term
                assertEquals(setOf("a.md", "b.md"), paths(q("common", "laravel", SearchMode.KEYWORD)))
                assertEquals(setOf("c.md"), paths(q("common", "php", SearchMode.HYBRID)))

                // filter-only (blank query) returns EXACTLY the tagged notes — in every mode
                assertEquals(setOf("a.md", "b.md"), paths(q("", "laravel", SearchMode.HYBRID)), "filter-only hybrid")
                assertEquals(setOf("a.md", "b.md"), paths(q("", "laravel", SearchMode.KEYWORD)), "filter-only keyword")
                assertEquals(setOf("a.md", "b.md"), paths(q("", "laravel", SearchMode.SEMANTIC)), "filter-only semantic")

                // a lone "*" is a deterministic match-all, so "*" + tag also yields exactly the tagged set
                assertEquals(setOf("a.md", "b.md"), paths(q("*", "laravel", SearchMode.HYBRID)), "star + tag")

                // blank query with NO filter returns nothing (the route 400s before this in the API)
                assertEquals(emptySet(), paths(q("", null, SearchMode.HYBRID)))
            }
        }
    }
}
