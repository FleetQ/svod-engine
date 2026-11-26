package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two additive recall guardrails, verified at the index layer:
 *  - `messy/` quarantine (Idea 3): drafts hidden from default recall, revealed by includeAll,
 *    the `includeMessyInRecall` toggle, or an explicit `messy/` pathPrefix browse.
 *  - `private` exclusion (Idea 2): `private: true` notes and `<private>…</private>` spans never
 *    reach the index (so neither FTS keyword search nor semantic recall can surface them).
 */
class RecallExclusionTest {

    private fun paths(r: SearchResult) = r.hits.map { it.path }.toSet()

    @Test
    fun `messy drafts are quarantined from default recall, but reachable via includeAll, toggle, or explicit prefix`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                fx.seed("messy/draft.md", "# Draft\ncommon body draft")
                fx.seed("curated/keep.md", "# Keep\ncommon body keep")
            }
            fun q(idx: IndexService, f: SearchFilters = SearchFilters()) =
                paths(idx.search(SearchQuery("common", f, SearchMode.KEYWORD, limit = 50)))

            fx.newIndex(FakeEmbedder("f")).use { idx ->
                assertTrue("curated/keep.md" in q(idx), "curated note visible by default")
                assertTrue("messy/draft.md" !in q(idx), "messy/ draft hidden from default recall")
                assertTrue("messy/draft.md" in q(idx, SearchFilters(includeAll = true)), "includeAll reveals messy/")
                assertTrue("messy/draft.md" in q(idx, SearchFilters(pathPrefix = "messy/")), "explicit messy/ browse is not blocked")
            }

            // Same index dir, but with the config toggle on ⇒ messy/ participates in default recall.
            IndexService(fx.root, fx.indexDir, FakeEmbedder("f"), includeMessyInRecall = true).start().use { idx ->
                assertTrue("messy/draft.md" in q(idx), "includeMessyInRecall=true un-quarantines messy/")
            }
        }
    }

    @Test
    fun `withheld counts lifecycle-hidden matches per reason`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                fx.seed("plain.md", "# Plain\ncommon body, no frontmatter at all")
                fx.seed("active.md", "---\ntype: fact\nstatus: active\n---\n# Active\ncommon body active")
                fx.seed("prov.md", "---\ntype: fact\nstatus: provisional\n---\n# Prov\ncommon body provisional")
                // Two sections → two chunks, still one note.
                fx.seed("revoked.md", "---\ntype: fact\nstatus: revoked\n---\n# Revoked\ncommon body revoked\n\n## More\ncommon again")
                // Revoked AND superseded → counted once, under revoked.
                fx.seed("both.md", "---\ntype: fact\nstatus: revoked\nsuperseded_by: active.md\n---\n# Both\ncommon body both")
                fx.seed("superseded.md", "---\ntype: fact\nsuperseded_by: active.md\n---\n# Old\ncommon body superseded")
                fx.seed("expired.md", "---\ntype: fact\nexpires_at: 2000-01-01\n---\n# Expired\ncommon body expired")
                fx.seed("future.md", "---\ntype: fact\nexpires_at: 2999-01-01\n---\n# Future\ncommon body future")
                // Not matching the query → not counted.
                fx.seed("other.md", "---\ntype: fact\nstatus: revoked\n---\n# Other\nunrelated words")
                // messy/ drafts are never counted as withheld.
                fx.seed("messy/draft.md", "---\ntype: fact\nstatus: revoked\n---\n# Draft\ncommon draft")
            }
            fx.newIndex(FakeEmbedder("f")).use { idx ->
                fun w(f: SearchFilters = SearchFilters(), text: String = "common") =
                    idx.search(SearchQuery(text, f, SearchMode.KEYWORD, limit = 50)).withheld

                val withheld = w()
                assertEquals(
                    mapOf(
                        SearchResult.WITHHELD_REVOKED to 2,
                        SearchResult.WITHHELD_SUPERSEDED to 1,
                        SearchResult.WITHHELD_EXPIRED to 1,
                        SearchResult.WITHHELD_PROVISIONAL to 1,
                    ),
                    withheld,
                )
                assertEquals(listOf("revoked", "superseded", "expired", "provisional"), withheld.keys.toList(), "fixed order")
                // includeAll hides nothing, so nothing is withheld.
                assertEquals(emptyMap<String, Int>(), w(SearchFilters(includeAll = true)))
                // A status filter turns off status hiding: only superseded/expired remain.
                assertEquals(emptyMap<String, Int>(), w(SearchFilters(status = "provisional")))
                // status=revoked: both.md is revoked AND superseded, so it is withheld as superseded.
                assertEquals(mapOf("superseded" to 1), w(SearchFilters(status = "revoked")))
                // Nothing hidden → empty.
                assertEquals(emptyMap<String, Int>(), w(text = "future"))
            }
        }
    }

    @Test
    fun `private notes and private spans never enter the index`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                fx.seed("notes/pub.md", "# Pub\ncommon visible <private>LEAK_SPAN_XYZ</private> tail")
                fx.seed("notes/whole.md", "---\nprivate: true\n---\n# Whole\ncommon LEAK_WHOLE_ABC")
            }
            fx.newIndex(FakeEmbedder("f")).use { idx ->
                fun q(text: String, f: SearchFilters = SearchFilters()) =
                    paths(idx.search(SearchQuery(text, f, SearchMode.KEYWORD, limit = 50)))

                // The public note is still indexed by its non-private text.
                assertTrue("notes/pub.md" in q("common"), "the non-private note is indexed")
                // The whole private note is gone from the index — even includeAll cannot surface it.
                assertTrue("notes/whole.md" !in q("common"), "private:true note excluded from recall")
                assertTrue("notes/whole.md" !in q("common", SearchFilters(includeAll = true)), "not indexed at all")
                // The private span's content is unsearchable.
                assertTrue(q("LEAK_SPAN_XYZ").isEmpty(), "span content is not indexed")
                assertTrue(q("LEAK_WHOLE_ABC").isEmpty(), "private-note body is not indexed")
                // And no returned snippet leaks the span.
                idx.search(SearchQuery("common", limit = 50)).hits.forEach {
                    assertTrue(!it.snippet.contains("LEAK"), "no snippet leaks private content")
                }
            }
        }
    }
}
