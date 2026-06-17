package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Memory typing (`type`) and lifecycle hiding (`status`/`superseded_by`/`expires_at`) as Lucene
 * filters. The critical invariant: a plain note (no reserved keys) is never hidden.
 */
class MemoryIndexTest {

    private fun paths(r: SearchResult) = r.hits.map { it.path }.toSet()

    @Test
    fun `typing and lifecycle filters`() {
        IndexFixture.create().use { fx ->
            runBlocking {
                fx.seed("plain.md", "# Plain\ncommon body, no frontmatter at all")
                fx.seed("active.md", "---\ntype: fact\nstatus: active\n---\n# Active\ncommon body active")
                fx.seed("prov.md", "---\ntype: fact\nstatus: provisional\n---\n# Prov\ncommon body provisional")
                fx.seed("revoked.md", "---\ntype: fact\nstatus: revoked\n---\n# Revoked\ncommon body revoked")
                fx.seed("policy.md", "---\ntype: policy\nstatus: active\n---\n# Policy\ncommon body policy")
                fx.seed("superseded.md", "---\ntype: fact\nsuperseded_by: active.md\n---\n# Old\ncommon body superseded")
                fx.seed("expired.md", "---\ntype: fact\nexpires_at: 2000-01-01\n---\n# Expired\ncommon body expired")
                fx.seed("future.md", "---\ntype: fact\nexpires_at: 2999-01-01\n---\n# Future\ncommon body future")
            }
            fx.newIndex(FakeEmbedder("f")).use { idx ->
                fun q(text: String, f: SearchFilters = SearchFilters(), mode: SearchMode = SearchMode.KEYWORD) =
                    paths(idx.search(SearchQuery(text, f, mode, limit = 50)))

                val defaultHits = q("common")
                // #2 backward compat — a plain note is fully visible
                assertTrue("plain.md" in defaultHits, "plain note must stay visible")
                // #5 active + #8 MUST_NOT anchor: a plain text query still returns active notes
                assertTrue(setOf("active.md", "policy.md", "future.md").all { it in defaultHits }, "active notes visible: $defaultHits")
                // #3/#4/#6/#7 default hiding
                assertTrue("revoked.md" !in defaultHits, "revoked hidden")
                assertTrue("prov.md" !in defaultHits, "provisional hidden")
                assertTrue("superseded.md" !in defaultHits, "superseded hidden")
                assertTrue("expired.md" !in defaultHits, "expired hidden")

                // #1 type filter
                assertEquals(setOf("policy.md"), q("common", SearchFilters(type = "policy")), "type filter")

                // #9 explicit status filter surfaces provisional
                assertEquals(setOf("prov.md"), q("common", SearchFilters(status = "provisional")), "explicit provisional")

                // includeAll reveals everything
                val all = q("common", SearchFilters(includeAll = true))
                assertTrue(setOf("revoked.md", "prov.md", "superseded.md", "expired.md").all { it in all }, "includeAll shows hidden: $all")

                // filter-only (blank query) by type also respects lifecycle (revoked fact excluded)
                assertEquals(setOf("active.md", "future.md"), q("", SearchFilters(type = "fact"), SearchMode.HYBRID), "filter-only type=fact, active only")
            }
        }
    }
}
