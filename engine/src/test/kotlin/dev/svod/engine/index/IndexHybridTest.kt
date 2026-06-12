package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexHybridTest {

    private fun epoch(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toEpochSecond()

    private suspend fun IndexFixture.seedCorpus() {
        seed("vault/cats.md", """
            ---
            tags: [animal, pet]
            created: 2024-01-15
            ---
            # Cats
            Cats are feline animals that purr and meow.
        """.trimIndent())
        seed("vault/dogs.md", """
            ---
            tags: [animal, pet]
            created: 2024-03-20
            ---
            # Dogs
            Dogs are loyal canine companions that bark.
        """.trimIndent())
        seed("notes/databases.md", """
            ---
            tags: [tech]
            created: 2023-12-01
            ---
            # Databases
            Relational databases store tables and rows with SQL.
            # Indexing
            A database index speeds up queries.
        """.trimIndent())
        seed("заметки/кошки.md", """
            ---
            tags: [животные]
            created: 2024-02-10
            ---
            # Кошки
            Кошки — пушистые домашние животные, которые мурлычут.
        """.trimIndent())
    }

    @Test
    fun `hybrid search ranks the right document first, including Cyrillic`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()
            val idx = fx.newIndex(FakeEmbedder("fake-v1"))
            try {
                val cats = idx.search(SearchQuery("cats purr", mode = SearchMode.HYBRID))
                assertTrue(cats.hits.isNotEmpty(), "expected hits")
                assertEquals("vault/cats.md", cats.hits.first().path)
                assertTrue(cats.hits.first().matchedKeyword || cats.hits.first().matchedSemantic)

                val cyr = idx.search(SearchQuery("кошки мурлычут", mode = SearchMode.HYBRID))
                assertEquals("заметки/кошки.md", cyr.hits.first().path, "Cyrillic query must find Cyrillic doc")
            } finally { idx.close() }
        }
    }

    @Test
    fun `tag, path and date filters constrain both retrieval legs`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()
            val idx = fx.newIndex(FakeEmbedder("fake-v1"))
            try {
                // tag filter
                val tech = idx.search(SearchQuery("index", SearchFilters(tags = listOf("tech"))))
                assertTrue(tech.hits.isNotEmpty())
                assertTrue(tech.hits.all { it.path == "notes/databases.md" }, "tag=tech must restrict to databases: ${tech.hits.map { it.path }}")

                // path prefix filter
                val vaultOnly = idx.search(SearchQuery("animals", SearchFilters(pathPrefix = "vault/")))
                assertTrue(vaultOnly.hits.isNotEmpty())
                assertTrue(vaultOnly.hits.all { it.path.startsWith("vault/") }, "pathPrefix must restrict: ${vaultOnly.hits.map { it.path }}")

                // date filter excludes the 2023 doc
                val dated = idx.search(SearchQuery("databases", SearchFilters(createdFrom = epoch("2024-01-01"))))
                assertTrue(dated.hits.none { it.path == "notes/databases.md" }, "2023 doc must be filtered out by createdFrom=2024")
            } finally { idx.close() }
        }
    }

    @Test
    fun `keyword query syntax - fuzzy, prefix, phrase, field-scoped`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()
            val idx = fx.newIndex(FakeEmbedder("fake-v1"))
            try {
                fun top(q: String) = idx.search(SearchQuery(q, mode = SearchMode.KEYWORD)).hits.firstOrNull()?.path

                assertEquals("vault/cats.md", top("catz~"), "fuzzy catz~ should match cats")
                assertEquals("notes/databases.md", top("data*"), "prefix data* should match databases")
                assertEquals("vault/cats.md", top("\"feline animals\""), "phrase should match cats")
                assertEquals("notes/databases.md", top("heading:Indexing"), "field-scoped heading query")
            } finally { idx.close() }
        }
    }

    @Test
    fun `keyword-only and semantic-only modes both work`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seedCorpus()
            val idx = fx.newIndex(FakeEmbedder("fake-v1"))
            try {
                val kw = idx.search(SearchQuery("canine bark", mode = SearchMode.KEYWORD))
                assertEquals("vault/dogs.md", kw.hits.first().path)
                assertTrue(kw.hits.all { it.matchedKeyword })

                val sem = idx.search(SearchQuery("dogs bark loyal", mode = SearchMode.SEMANTIC))
                assertEquals("vault/dogs.md", sem.hits.first().path)
                assertTrue(sem.hits.all { it.matchedSemantic })
            } finally { idx.close() }
        }
    }
}
