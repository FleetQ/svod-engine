package dev.svod.engine.api

import dev.svod.engine.core.Author
import dev.svod.engine.core.GuardedWrite
import dev.svod.engine.events.EventTypes
import dev.svod.engine.events.SvodEvent
import dev.svod.engine.index.MarkdownChunker
import dev.svod.engine.index.SearchFilters
import dev.svod.engine.index.SearchMode
import dev.svod.engine.index.SearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The memory review queue (`/api/v1/memory/review`) and the rule book (`/api/v1/memory/rulebook`). */
class MemoryReviewApiTest {

    private val UI = Author("ui", "ui@svod.local")

    private fun obj(body: String) = Json.parseToJsonElement(body).jsonObject
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    private suspend fun note(fx: ApiFixture, path: String, frontmatter: String, body: String) {
        fx.engine.write(path, "---\n$frontmatter\n---\n$body", null, UI)
    }

    private fun review(fx: ApiFixture, query: String = ""): JsonObject {
        val r = fx.get("/api/v1/memory/review$query")
        assertEquals(200, r.statusCode(), r.body())
        return obj(r.body())
    }

    private fun items(list: JsonObject) = list["items"]!!.jsonArray.map { it.jsonObject }
    private fun paths(list: JsonObject) = items(list).map { it.s("path")!! }

    private fun act(fx: ApiFixture, path: String, action: String, expectedRevision: String? = null) =
        fx.post("/api/v1/memory/review", Json.encodeToString(MemoryReviewActionDto.serializer(), MemoryReviewActionDto(path, action, expectedRevision)))

    private suspend fun searchPaths(fx: ApiFixture, text: String): Set<String> {
        fx.index.waitIdle()
        return fx.index.search(SearchQuery(text, SearchFilters(), SearchMode.KEYWORD, 20)).hits.map { it.path }.toSet()
    }

    // ---- review list ----

    @Test
    fun `R1 only provisional memories that are not revoked or superseded are listed`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            note(fx, "memory/fact/p.md", "type: fact\nstatus: provisional", "Provisional fact.")
            note(fx, "memory/fact/a.md", "type: fact\nstatus: active", "Active fact.")
            note(fx, "memory/fact/r.md", "type: fact\nstatus: revoked", "Revoked fact.")
            note(fx, "memory/fact/s.md", "type: fact\nstatus: provisional\nsuperseded_by: memory/fact/p.md", "Superseded fact.")
            fx.index.waitIdle()

            val list = review(fx)
            assertEquals(listOf("memory/fact/p.md"), paths(list))
            assertEquals(1, list["total"]!!.jsonPrimitive.int)
            val item = items(list).single()
            assertEquals("fact", item.s("type")); assertEquals("provisional", item.s("status"))
            assertEquals("false", item.s("needsReview"))
            assertEquals(fx.engine.read("memory/fact/p.md")!!.revision, item.s("revision"))
        }
    }

    @Test
    fun `R2 an active memory flagged needs-review is listed through the index term`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            note(fx, "memory/fact/u.md", "type: fact\nstatus: active\nneeds-review: true", "Uncertain fact.")
            fx.index.waitIdle()

            val list = review(fx)
            assertEquals(listOf("memory/fact/u.md"), paths(list))
            assertEquals("true", items(list).single().s("needsReview"))
        }
    }

    @Test
    fun `R3 private notes are not listed and private spans are masked in the excerpt`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            note(fx, "memory/fact/hidden.md", "type: fact\nstatus: provisional\nprivate: true", "Whole note is private.")
            note(fx, "memory/fact/masked.md", "type: fact\nstatus: provisional", "Visible start <private>api password hunter2</private> visible end.")
            fx.index.waitIdle()

            val list = review(fx)
            assertEquals(listOf("memory/fact/masked.md"), paths(list))
            val excerpt = items(list).single().s("excerpt")!!
            assertFalse(excerpt.contains("hunter2"), excerpt)
            assertTrue(excerpt.startsWith("Visible start") && excerpt.endsWith("visible end."), excerpt)
        }
    }

    @Test
    fun `R4 a captured session carrying status provisional is never listed`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            note(fx, "messy/sessions/2026/s.md", "type: fact\nstatus: provisional", "Transcript.")
            note(fx, "messy/draft.md", "type: fact\nstatus: provisional", "A draft memory.")
            fx.index.waitIdle()

            assertEquals(listOf("messy/draft.md"), paths(review(fx)))
        }
    }

    @Test
    fun `R5 needs-review and contradicting memories come first, then newest, and limits are capped`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            note(fx, "memory/fact/old.md", "type: fact\nstatus: provisional\ncreated: 2026-01-01T00:00:00Z", "Old.")
            note(fx, "memory/fact/new.md", "type: fact\nstatus: provisional\ncreated: 2026-03-01T00:00:00Z", "New.")
            note(fx, "memory/fact/unsure.md", "type: fact\nstatus: provisional\nneeds-review: true\ncreated: 2025-01-01T00:00:00Z", "Unsure.")
            note(fx, "memory/fact/clash.md", "type: fact\nstatus: provisional\ncontradicts: memory/fact/old.md\ncreated: 2025-02-01T00:00:00Z", "Clash.")
            fx.index.waitIdle()

            assertEquals(
                listOf("memory/fact/clash.md", "memory/fact/unsure.md", "memory/fact/new.md", "memory/fact/old.md"),
                paths(review(fx)),
            )
            val limited = review(fx, "?limit=2")
            assertEquals(listOf("memory/fact/clash.md", "memory/fact/unsure.md"), paths(limited))
            assertEquals(4, limited["total"]!!.jsonPrimitive.int, "total counts past the limit")
        }
    }

    @Test
    fun `R5 limit is capped at 500`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            val files = (1..501).associate { "memory/fact/n$it.md" to "---\ntype: fact\nstatus: provisional\n---\nFact number $it.\n" }
            assertTrue(fx.engine.writeGuarded(files, files.keys.associateWith { null }, UI, "seed") is GuardedWrite.Applied)
            fx.index.waitIdle()

            val list = review(fx, "?limit=1000")
            assertEquals(500, items(list).size)
            assertEquals(501, list["total"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `R6 a note changed on disk after indexing is judged by its current frontmatter`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            note(fx, "memory/fact/lag.md", "type: fact\nstatus: provisional", "Lagging fact.")
            fx.index.waitIdle()
            assertEquals(listOf("memory/fact/lag.md"), paths(review(fx)))

            // Behind the engine's back, so the index still says provisional.
            Files.writeString(fx.root.resolve("memory/fact/lag.md"), "---\ntype: fact\nstatus: active\n---\nLagging fact.")
            val list = review(fx)
            assertEquals(emptyList(), paths(list))
            assertEquals(0, list["total"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `R3 a note that turned private on disk before reindexing is neither listed nor counted`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            note(fx, "memory/fact/secret.md", "type: fact\nstatus: provisional", "Soon private.")
            fx.index.waitIdle()
            assertEquals(1, review(fx)["total"]!!.jsonPrimitive.int)

            // Behind the engine's back, so the index still lists it as provisional.
            Files.writeString(fx.root.resolve("memory/fact/secret.md"), "---\ntype: fact\nstatus: provisional\nprivate: true\n---\nSoon private.")
            val list = review(fx)
            assertEquals(emptyList(), paths(list))
            assertEquals(0, list["total"]!!.jsonPrimitive.int)
            assertEquals(0, obj(fx.get("/api/v1/memory/dashboard").body())["awaitingReview"]!!.jsonPrimitive.int)
            assertEquals(0, rulebook(fx)["awaitingReview"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `R7 Cyrillic path and body round-trip through list and approve`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            val path = "memory/факт/бележка.md"
            note(fx, path, "type: fact\nstatus: provisional\nsubject: база", "Базата е в Германия.")
            fx.index.waitIdle()

            val item = items(review(fx)).single()
            assertEquals(path, item.s("path"))
            assertEquals("Базата е в Германия.", item.s("excerpt"))
            assertEquals("база", item.s("subject"))
            val r = act(fx, path, "approve")
            assertEquals(200, r.statusCode(), r.body())
            assertEquals("active", MarkdownChunker.parse(fx.engine.read(path)!!.text).status)
            assertTrue(fx.engine.read(path)!!.text.endsWith("Базата е в Германия."))
        }
    }

    // ---- review actions ----

    @Test
    fun `A1 approve activates the memory in one commit by the principal and keeps the body`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            val path = "memory/fact/approve.md"
            val body = "Prod DB lives in eu-west-1.\n\n  indented line kept\n"
            note(fx, path, "type: fact\nstatus: provisional\nsubject: infra\nconfidence: 0.8\nneeds-review: true\ncreated: 2026-09-01T10:00:00Z", body)
            fx.index.waitIdle()
            assertFalse("memory/fact/approve.md" in searchPaths(fx, "eu-west-1"), "provisional is hidden before")
            val before = fx.engine.read(path)!!
            val beforeDoc = MarkdownChunker.parse(before.text)
            val commitsBefore = fx.engine.history(path).size

            val r = act(fx, path, "approve", before.revision)
            assertEquals(200, r.statusCode(), r.body())
            val res = obj(r.body())
            assertEquals(path, res.s("path")); assertEquals("active", res.s("status"))
            val after = fx.engine.read(path)!!
            assertEquals(after.revision, res.s("revision"))

            val doc = MarkdownChunker.parse(after.text)
            assertEquals("active", doc.status)
            assertFalse(doc.needsReview)
            assertEquals(beforeDoc.body, doc.body, "body byte-identical")
            assertEquals(
                beforeDoc.frontmatter.keys.filter { it != "needs-review" } + listOf("reviewed_at", "reviewed_by"),
                doc.frontmatter.keys.toList(),
                "other keys kept in order",
            )
            for (k in listOf("type", "subject", "confidence", "created")) assertEquals(beforeDoc.frontmatter[k], doc.frontmatter[k], k)
            assertEquals("svod-ui", doc.frontmatter["reviewed_by"])
            assertNotNull(java.time.Instant.parse(doc.frontmatter["reviewed_at"].toString()))

            val history = fx.engine.history(path)
            assertEquals(commitsBefore + 1, history.size, "exactly one commit")
            assertEquals("svod-ui", history.first().authorName)
            assertEquals(res.s("commit"), history.first().commit)
            assertTrue(path in searchPaths(fx, "eu-west-1"), "approved memory is recallable")
        }
    }

    @Test
    fun `A1 review actions leave untouched frontmatter lines byte-identical`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            val path = "memory/fact/handwritten.md"
            val kept = listOf(
                "type: fact",
                "# written by hand, keep this comment",
                "created: 2026-09-01T10:00:00Z",
                "date: 2026-09-01",
                "subject: \"база данни\"",
            )
            val raw = "---\n${kept[0]}\n${kept[1]}\nstatus: provisional\n${kept[2]}\n${kept[3]}\n${kept[4]}\nneeds-review: true\n---\nBody line.\n"
            fx.engine.write(path, raw, null, UI)

            for (action in listOf("approve", "reopen", "decline", "reopen")) {
                val r = act(fx, path, action)
                assertEquals(200, r.statusCode(), r.body())
                val text = fx.engine.read(path)!!.text
                val lines = text.lines()
                for (line in kept) assertTrue(line in lines, "after $action, '$line' is gone:\n$text")
                assertEquals(kept, lines.filter { it in kept }, "after $action the kept lines stay in order")
                assertTrue(text.endsWith("---\nBody line.\n"), text)
                assertEquals(obj(r.body()).s("status"), MarkdownChunker.parse(text).status)
            }
            val last = fx.engine.read(path)!!.text
            assertFalse("needs-review" in last, last)
            assertEquals(1, last.lines().count { it.startsWith("reviewed_at:") }, last)
        }
    }

    @Test
    fun `A2 decline revokes the memory and keeps it hidden`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            val path = "memory/fact/decline.md"
            note(fx, path, "type: fact\nstatus: active\nneeds-review: true", "Staging uses zebrafish.")
            fx.index.waitIdle()
            assertTrue(path in searchPaths(fx, "zebrafish"))

            val r = act(fx, path, "decline")
            assertEquals(200, r.statusCode(), r.body())
            assertEquals("revoked", obj(r.body()).s("status"))
            val doc = MarkdownChunker.parse(fx.engine.read(path)!!.text)
            assertEquals("revoked", doc.status); assertFalse(doc.needsReview)
            assertFalse(path in searchPaths(fx, "zebrafish"), "revoked memory is hidden")
            assertEquals(emptyList(), paths(review(fx)))
        }
    }

    @Test
    fun `A3 reopen after approve and after decline makes the memory provisional again`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            val path = "memory/fact/reopen.md"
            note(fx, path, "type: fact\nstatus: provisional", "Deploys happen on quokka day.")
            fx.index.waitIdle()

            for (first in listOf("approve", "decline")) {
                assertEquals(200, act(fx, path, first).statusCode())
                val r = act(fx, path, "reopen")
                assertEquals(200, r.statusCode(), r.body())
                assertEquals("provisional", obj(r.body()).s("status"))
                assertEquals("provisional", MarkdownChunker.parse(fx.engine.read(path)!!.text).status)
                assertFalse(path in searchPaths(fx, "quokka"), "hidden again after reopening a $first")
                assertEquals(listOf(path), paths(review(fx)))
            }
        }
    }

    @Test
    fun `A4 a stale expectedRevision is a 409 conflict and leaves the file alone`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            val path = "memory/fact/stale.md"
            note(fx, path, "type: fact\nstatus: provisional", "First.")
            val stale = fx.engine.read(path)!!.revision
            fx.engine.write(path, "---\ntype: fact\nstatus: provisional\n---\nSecond.", stale, UI)
            val current = fx.engine.read(path)!!

            val r = act(fx, path, "approve", stale)
            assertEquals(409, r.statusCode(), r.body())
            val body = obj(r.body())
            assertEquals(path, body.s("path")); assertEquals(current.revision, body.s("current"))
            assertEquals(current, fx.engine.read(path), "file unchanged")
        }
    }

    @Test
    fun `A5 non-memory, missing, bad action and superseded are refused`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            fx.engine.write("notes/plain.md", "# Plain\nNo frontmatter.", null, UI)
            note(fx, "notes/tagged.md", "tags: [x]", "Frontmatter without status or type.")
            note(fx, "memory/fact/old.md", "type: fact\nstatus: revoked\nsuperseded_by: memory/fact/new.md", "Old.")
            note(fx, "memory/fact/ok.md", "type: fact\nstatus: provisional", "Fine.")

            assertEquals(400, act(fx, "notes/plain.md", "approve").statusCode())
            assertEquals(400, act(fx, "notes/tagged.md", "approve").statusCode())
            assertEquals(404, act(fx, "memory/fact/missing.md", "approve").statusCode())
            val bad = act(fx, "memory/fact/ok.md", "delete")
            assertEquals(400, bad.statusCode()); assertEquals("bad_request", obj(bad.body()).s("error"))
            val sup = act(fx, "memory/fact/old.md", "reopen")
            assertEquals(409, sup.statusCode(), sup.body()); assertEquals("superseded", obj(sup.body()).s("error"))
            assertEquals("revoked", MarkdownChunker.parse(fx.engine.read("memory/fact/old.md")!!.text).status)
            assertEquals("provisional", MarkdownChunker.parse(fx.engine.read("memory/fact/ok.md")!!.text).status)
        }
    }

    @Test
    fun `A7 a review publishes commit created with tool memory review and the vault`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            val path = "memory/fact/event.md"
            note(fx, path, "type: fact\nstatus: provisional", "Event fact.")
            val received = CopyOnWriteArrayList<SvodEvent>()
            val job = launch(Dispatchers.Default) { fx.eventBus.events.collect { received.add(it) } }
            delay(200)

            assertEquals(200, act(fx, path, "approve").statusCode())
            withTimeout(5000) { while (received.none { it.type == EventTypes.COMMIT_CREATED }) delay(20) }
            val e = received.first { it.type == EventTypes.COMMIT_CREATED }
            assertEquals("memory.review", e.data["tool"]!!.jsonPrimitive.content)
            assertEquals(path, e.data["path"]!!.jsonPrimitive.content)
            assertTrue(!e.data["vault"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty(), "event carries the vault: ${e.data}")
            job.cancel()
        }
    }

    @Test
    fun `A8 the dashboard awaitingReview equals the queue total`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            note(fx, "memory/fact/one.md", "type: fact\nstatus: provisional", "One.")
            note(fx, "memory/policy/two.md", "type: policy\nstatus: provisional", "Two.")
            note(fx, "memory/fact/three.md", "type: fact\nstatus: active\nneeds-review: true", "Three.")
            note(fx, "memory/fact/four.md", "type: fact\nstatus: active", "Four.")
            fx.index.waitIdle()

            val dash = obj(fx.get("/api/v1/memory/dashboard").body())
            assertEquals(3, dash["awaitingReview"]!!.jsonPrimitive.int)
            assertEquals(review(fx, "?limit=1")["total"]!!.jsonPrimitive.int, dash["awaitingReview"]!!.jsonPrimitive.int)
        }
    }

    // ---- rule book ----

    private fun rulebook(fx: ApiFixture, query: String = ""): JsonObject {
        val r = fx.get("/api/v1/memory/rulebook$query")
        assertEquals(200, r.statusCode(), r.body())
        return obj(r.body())
    }

    @Test
    fun `B1 the rule book lists active policies and preferences only`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            note(fx, "memory/policy/a.md", "type: policy\nstatus: active\ntitle: Zeta rule\nsubject: deploy", "Deploy on Tuesdays.")
            note(fx, "memory/policy/b.md", "type: policy\nstatus: active\ntitle: Alpha rule", "Write tests first.")
            note(fx, "memory/preference/c.md", "type: preference\nstatus: active", "Terse answers.")
            note(fx, "memory/policy/prov.md", "type: policy\nstatus: provisional", "Provisional policy.")
            note(fx, "memory/policy/rev.md", "type: policy\nstatus: revoked", "Revoked policy.")
            note(fx, "memory/policy/sup.md", "type: policy\nstatus: active\nsuperseded_by: memory/policy/a.md", "Superseded policy.")
            note(fx, "memory/policy/exp.md", "type: policy\nstatus: active\nexpires_at: 2001-01-01T00:00:00Z", "Expired policy.")
            note(fx, "messy/policy.md", "type: policy\nstatus: active", "Draft policy.")
            note(fx, "memory/fact/f.md", "type: fact\nstatus: active", "A fact.")
            fx.index.waitIdle()

            val book = rulebook(fx)
            val rows = book["items"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("memory/policy/b.md", "memory/policy/a.md", "memory/preference/c.md"), rows.map { it.s("path") })
            assertEquals(listOf("Alpha rule", "Zeta rule", "Terse answers."), rows.map { it.s("title") })
            assertEquals("deploy", rows[1].s("subject"))
            assertEquals(1, book["awaitingReview"]!!.jsonPrimitive.int)

            val facts = rulebook(fx, "?types=fact")["items"]!!.jsonArray.map { it.jsonObject.s("path") }
            assertEquals(listOf("memory/fact/f.md"), facts)
        }
    }

    @Test
    fun `B2 the summary is the first non-heading line, masked and bounded, and private notes are excluded`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            note(fx, "memory/policy/s.md", "type: policy\nstatus: active", "# Heading\n\nKeep <private>token abc</private> it short.\nSecond line.")
            note(fx, "memory/policy/long.md", "type: policy\nstatus: active", "x".repeat(300))
            note(fx, "memory/policy/p.md", "type: policy\nstatus: active\nprivate: true", "Private policy.")
            fx.index.waitIdle()

            val rows = rulebook(fx)["items"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("path") }
            assertEquals(setOf("memory/policy/s.md", "memory/policy/long.md"), rows.keys)
            val summary = rows["memory/policy/s.md"]!!.s("summary")!!
            assertTrue(summary.startsWith("Keep") && summary.endsWith("it short."), summary)
            assertFalse(summary.contains("abc"), summary)
            assertEquals("Heading", rows["memory/policy/s.md"]!!.s("title"))
            assertEquals(160, rows["memory/policy/long.md"]!!.s("summary")!!.length)
        }
    }

    @Test
    fun `B3 rule book limit defaults to 40 and is capped at 200`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            val files = (1..201).associate { "memory/policy/p$it.md" to "---\ntype: policy\nstatus: active\n---\nPolicy $it.\n" }
            assertTrue(fx.engine.writeGuarded(files, files.keys.associateWith { null }, UI, "seed") is GuardedWrite.Applied)
            fx.index.waitIdle()

            val book = rulebook(fx)
            assertEquals(40, book["items"]!!.jsonArray.size)
            assertEquals(0, book["awaitingReview"]!!.jsonPrimitive.int)
            assertEquals(200, rulebook(fx, "?limit=1000")["items"]!!.jsonArray.size)
        }
    }
}
