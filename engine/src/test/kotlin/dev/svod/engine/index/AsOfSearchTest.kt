package dev.svod.engine.index

import dev.svod.engine.core.WriteOutcome
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Point-in-time recall (`asOf`): search answers from the vault as it was committed at a time,
 * from git history, with lifecycle filters judged at that time. Git commit time is
 * second-granular, so the commits here are made more than a second apart.
 */
class AsOfSearchTest {

    private fun paths(r: SearchResult) = r.hits.map { it.path }.toSet()

    private suspend fun IndexFixture.put(path: String, content: String, rev: String? = null): String {
        val out = engine.write(path, content, rev, INDEXER)
        return (out as WriteOutcome.Success).revision
    }

    private suspend fun IndexFixture.lastCommit(path: String) = engine.history(path, 1).first()

    @Test
    fun `commit lookup returns the commit at or before a time and null before the first commit`() {
        IndexFixture.create().use { fx ->
            val (c1, c2) = runBlocking {
                fx.put("a.md", "# A\nfirst version")
                val c1 = fx.lastCommit("a.md")
                Thread.sleep(1100)
                fx.put("b.md", "# B\nanother note")
                c1 to fx.lastCommit("b.md")
            }
            GitReader(fx.root).use { r ->
                assertEquals(c1.commit, r.commitAtOrBefore(c1.epochSeconds))
                assertEquals(c2.commit, r.commitAtOrBefore(c2.epochSeconds))
                assertEquals(c2.commit, r.commitAtOrBefore(c2.epochSeconds + 3600))
                assertNull(r.commitAtOrBefore(c1.epochSeconds - 3600))

                val touch = assertNotNull(r.lastCommitTouching(c2.commit, "a.md"))
                assertEquals(c1.commit, touch.first)
                assertEquals(INDEXER.name, touch.second)
                assertNull(r.lastCommitTouching(c1.commit, "b.md"))
            }
        }
    }

    @Test
    fun `search as of a time sees that commit's text, not the current one`() {
        IndexFixture.create().use { fx ->
            val t1 = runBlocking {
                val rev = fx.put("note.md", "# Note\nthe old codename is walrus")
                val t1 = fx.lastCommit("note.md")
                Thread.sleep(1100)
                fx.put("note.md", "# Note\nthe new codename is pelican", rev)
                t1
            }
            fx.newIndex(NoneEmbedder).use { idx ->
                // Live search: current text only.
                assertTrue("note.md" in paths(idx.search(SearchQuery("pelican", mode = SearchMode.KEYWORD))))
                assertTrue(paths(idx.search(SearchQuery("walrus", mode = SearchMode.KEYWORD))).isEmpty())

                val old = idx.search(SearchQuery("walrus", asOfEpoch = t1.epochSeconds))
                assertEquals(setOf("note.md"), paths(old))
                assertEquals(t1.commit, old.asOfCommit)
                assertEquals(SearchMode.KEYWORD, old.mode)
                assertTrue(paths(idx.search(SearchQuery("pelican", asOfEpoch = t1.epochSeconds))).isEmpty())

                assertTrue(idx.readAt(t1.commit, "note.md")!!.contains("walrus"))
                assertEquals(t1.commit, idx.provenanceAt(t1.commit, "note.md")?.first)
            }
        }
    }

    @Test
    fun `lifecycle filters are judged at the as-of time`() {
        IndexFixture.create().use { fx ->
            val tomorrow = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1)
            val t1 = runBlocking {
                val rev = fx.put("fact.md", "---\ntype: fact\nstatus: active\n---\n# Fact\nthe gizmo runs nightly")
                fx.put("soon.md", "---\ntype: fact\nexpires_at: $tomorrow\n---\n# Soon\nthe gizmo expires soon")
                val t1 = fx.lastCommit("soon.md")
                Thread.sleep(1100)
                fx.put("fact.md", "---\ntype: fact\nstatus: revoked\n---\n# Fact\nthe gizmo runs nightly", rev)
                t1
            }
            fx.newIndex(NoneEmbedder).use { idx ->
                val live = paths(idx.search(SearchQuery("gizmo", mode = SearchMode.KEYWORD)))
                assertEquals(setOf("soon.md"), live, "revoked now, not yet expired")

                // At t1 the fact was still active.
                assertEquals(setOf("fact.md", "soon.md"), paths(idx.search(SearchQuery("gizmo", asOfEpoch = t1.epochSeconds))))

                // Two days from now resolves to HEAD, where the fact is revoked and `soon` has expired.
                val later = java.time.Instant.now().plusSeconds(2 * 86_400).epochSecond
                assertEquals(emptySet(), paths(idx.search(SearchQuery("gizmo", asOfEpoch = later))))

                val (commit, enumerated) = idx.enumerateAsOf(SearchFilters(type = "fact"), t1.epochSeconds, 50)
                assertEquals(t1.commit, commit)
                assertEquals(listOf("fact.md", "soon.md"), enumerated)
            }
        }
    }

    @Test
    fun `a time before the first commit gives an empty result`() {
        IndexFixture.create().use { fx ->
            val t1 = runBlocking {
                fx.put("note.md", "# Note\nwalrus")
                fx.lastCommit("note.md")
            }
            fx.newIndex(NoneEmbedder).use { idx ->
                val r = idx.search(SearchQuery("walrus", asOfEpoch = t1.epochSeconds - 86_400))
                assertTrue(r.hits.isEmpty())
                assertNull(r.asOfCommit)
            }
        }
    }
}
