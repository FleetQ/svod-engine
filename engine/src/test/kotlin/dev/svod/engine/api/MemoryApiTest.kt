package dev.svod.engine.api

import dev.svod.engine.core.Author
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The /api/v1/memory endpoints: capture idempotency, session listing/filtering, distill, proposals. */
class MemoryApiTest {

    private val UI = Author("ui", "ui@svod.local")

    private fun obj(body: String) = Json.parseToJsonElement(body).jsonObject
    private fun arr(body: String) = Json.parseToJsonElement(body).jsonArray
    private fun str(o: kotlinx.serialization.json.JsonObject, k: String) = o[k]!!.jsonPrimitive.content

    // Test transcripts/projects are plain ASCII (no quotes/backslashes), so inline JSON is safe here.
    private fun capture(fx: ApiFixture, sessionId: String, project: String?, transcript: String, endedAt: Long, startedAt: Long = 1000): String {
        val proj = if (project == null) "" else """"project":"$project","""
        val body = """{"sessionId":"$sessionId",$proj"transcript":"$transcript","startedAt":$startedAt,"endedAt":$endedAt}"""
        return fx.post("/api/v1/memory/capture", body).body()
    }

    private fun flag(o: kotlinx.serialization.json.JsonObject, k: String) = o[k]!!.jsonPrimitive.content.toBoolean()

    @Test
    fun `a longer transcript for a captured session rewrites the same note`() = runBlocking {
        ApiFixture.create().use { fx ->
            val first = obj(capture(fx, "sess-aaaa1111", "proj", "hello transcript", 1700))
            assertEquals(false, flag(first, "deduped"), first.toString())
            assertEquals(false, flag(first, "updated"), first.toString())
            val path = str(first, "path")
            assertTrue(path.startsWith("messy/sessions/"), path)

            // The hook fires again later in the same session: the transcript has grown. This is the case
            // that used to come back deduped, leaving only the first turn of every session stored.
            val longer = "hello transcript and two more turns"
            val second = obj(capture(fx, "sess-aaaa1111", "proj", longer, 9999, startedAt = 5000))
            assertEquals(false, flag(second, "deduped"), second.toString())
            assertEquals(true, flag(second, "updated"), second.toString())
            assertEquals(path, str(second, "path"), "the note keeps its path")

            val sessions = arr(fx.get("/api/v1/memory/sessions").body())
            assertEquals(1, sessions.size, "still one note for the sessionId")
            val s = sessions[0].jsonObject
            assertEquals(longer.length.toString(), str(s, "bytes"))
            assertEquals("1000", str(s, "startedAt"), "startedAt keeps the earliest value")
            assertEquals("9999", str(s, "endedAt"))
            assertTrue(fx.engine.read(path)!!.text.endsWith(longer), "the body is the new transcript")
        }
    }

    @Test
    fun `an equal or shorter transcript never overwrites a captured session`() = runBlocking {
        ApiFixture.create().use { fx ->
            val full = "the full long transcript"
            val path = str(obj(capture(fx, "sess-bbbb2222", "proj", full, 1700)), "path")
            val revision = fx.engine.read(path)!!.revision

            for (late in listOf(full.uppercase(), "short")) { // same size, then smaller
                val r = obj(capture(fx, "sess-bbbb2222", "proj", late, 9999))
                assertEquals(true, flag(r, "deduped"), r.toString())
                assertEquals(false, flag(r, "updated"), r.toString())
                assertEquals(path, str(r, "path"))
            }
            assertEquals(revision, fx.engine.read(path)!!.revision, "nothing was written")
            assertTrue(fx.engine.read(path)!!.text.endsWith(full), "the stored session did not shrink")
        }
    }

    @Test
    fun `concurrent captures of one session never leave it smaller than a capture that was written`() = runBlocking {
        ApiFixture.create().use { fx ->
            capture(fx, "sess-race0000", "proj", "x".repeat(10), 1000)
            repeat(10) { round ->
                // Every size in a round beats what is stored, so each request passes the size check it
                // reads; only the revision guard can stop a smaller one from landing after a larger one.
                val sizes = (1..8).map { 100 * (round + 1) + it * 7 }.shuffled()
                val written = java.util.concurrent.ConcurrentLinkedQueue<Int>()
                sizes.map { size ->
                    async(Dispatchers.IO) {
                        val body = """{"sessionId":"sess-race0000","project":"proj","transcript":"${"x".repeat(size)}","startedAt":1000,"endedAt":${2000 + size}}"""
                        val r = fx.post("/api/v1/memory/capture", body)
                        if (r.statusCode() == 200 && !flag(obj(r.body()), "deduped")) written += size
                    }
                }.awaitAll()
                val stored = str(arr(fx.get("/api/v1/memory/sessions").body()).single().jsonObject, "bytes").toInt()
                assertEquals(written.maxOrNull() ?: stored, stored, "round $round: writes that succeeded were $written")
            }
        }
    }

    @Test
    fun `a distilled session that grows is undistilled again`() = runBlocking {
        ApiFixture.create().use { fx ->
            val path = str(obj(capture(fx, "sess-cccc3333", "proj", "first part", 1000)), "path")
            fx.post("/api/v1/memory/sessions/mark-distilled", """{"paths":["$path"],"noteRefs":[]}""")
            assertEquals(1, arr(fx.get("/api/v1/memory/sessions?distilled=true").body()).size)

            capture(fx, "sess-cccc3333", "proj", "first part and the rest of the session", 2000)

            assertEquals(0, arr(fx.get("/api/v1/memory/sessions?distilled=true").body()).size, "the new tail is not distilled")
            assertEquals(1, arr(fx.get("/api/v1/memory/sessions?distilled=false").body()).size)
        }
    }

    @Test
    fun `sessions filter by distilled and mark-distilled moves dashboard counts`() = runBlocking {
        ApiFixture.create().use { fx ->
            val p1 = str(obj(capture(fx, "s1", "proj", "transcript one 12345", 1000)), "path")
            capture(fx, "s2", "proj", "transcript two 67890", 2000)

            // Both start not-distilled.
            assertEquals(2, arr(fx.get("/api/v1/memory/sessions?distilled=false").body()).size)
            assertEquals(0, arr(fx.get("/api/v1/memory/sessions?distilled=true").body()).size)

            // A curated note the "distiller" produced, referenced by mark-distilled for byte math.
            fx.engine.write("memory/curated.md", "distilled summary", null, UI)

            val before = obj(fx.get("/api/v1/memory/dashboard").body())
            assertEquals(2, before["sessionsCaptured"]!!.jsonPrimitive.int(), before.toString())
            assertEquals(0, before["sessionsDistilled"]!!.jsonPrimitive.int())

            val marked = fx.post("/api/v1/memory/sessions/mark-distilled",
                """{"paths":["$p1"],"noteRefs":["memory/curated.md"]}""")
            assertEquals(1, obj(marked.body())["updated"]!!.jsonPrimitive.int(), marked.body())

            // Newest-first ordering: s2 (endedAt 2000) precedes s1 (1000).
            val all = arr(fx.get("/api/v1/memory/sessions").body())
            assertEquals("s2", str(all[0].jsonObject, "sessionId"))

            assertEquals(1, arr(fx.get("/api/v1/memory/sessions?distilled=true").body()).size)

            val after = obj(fx.get("/api/v1/memory/dashboard").body())
            assertEquals(1, after["sessionsDistilled"]!!.jsonPrimitive.int(), after.toString())
            assertEquals(1, after["notesWritten"]!!.jsonPrimitive.int())
            assertNotEquals("null", after["lastDistillAt"].toString())
        }
    }

    @Test
    fun `dashboard compression ratio is capturedBytes over distilledBytes`() = runBlocking {
        ApiFixture.create().use { fx ->
            // transcript of 100 bytes, curated note of 10 bytes → ratio 10.0
            capture(fx, "s1", null, "x".repeat(100), 1000)
            fx.engine.write("memory/c.md", "y".repeat(10), null, UI)
            fx.post("/api/v1/memory/sessions/mark-distilled",
                """{"paths":[],"noteRefs":["memory/c.md"]}""")

            val d = obj(fx.get("/api/v1/memory/dashboard").body())
            assertEquals(100, d["capturedBytes"]!!.jsonPrimitive.int(), d.toString())
            assertEquals(10, d["distilledBytes"]!!.jsonPrimitive.int())
            assertEquals(10.0, d["compressionRatio"]!!.jsonPrimitive.content.toDouble(), d.toString())
        }
    }

    @Test
    fun `proposal lifecycle append list accept reject`() = runBlocking {
        ApiFixture.create().use { fx ->
            val created = obj(fx.post("/api/v1/memory/proposals",
                """{"kind":"skill","title":"Distill retros","scope":"project","confidence":0.8,"rationale":"seen often","sourceSessions":["s1"]}""").body())
            val id = str(created, "id")

            // Dedup by (kind,title,scope): same triple → same id, no second row.
            val dup = obj(fx.post("/api/v1/memory/proposals",
                """{"kind":"skill","title":"Distill retros","scope":"project","confidence":0.9,"rationale":"again","sourceSessions":["s2"]}""").body())
            assertEquals(id, str(dup, "id"))

            val open = arr(fx.get("/api/v1/memory/proposals").body())
            assertEquals(1, open.size, open.toString())
            assertEquals("open", str(open[0].jsonObject, "status"))

            // Accept is a status transition only.
            val accepted = obj(fx.post("/api/v1/memory/proposals/$id", """{"action":"accept","note":"lgtm"}""").body())
            assertEquals("accepted", str(accepted, "status"))
            assertEquals("lgtm", str(accepted, "note"))

            assertEquals(0, arr(fx.get("/api/v1/memory/proposals?status=open").body()).size)
            assertEquals(1, arr(fx.get("/api/v1/memory/proposals?status=accepted").body()).size)

            // Reject path on a second proposal.
            val other = str(obj(fx.post("/api/v1/memory/proposals",
                """{"kind":"tool","title":"CSV parser","scope":"global","confidence":0.5,"rationale":"x","sourceSessions":[]}""").body()), "id")
            val rejected = obj(fx.post("/api/v1/memory/proposals/$other", """{"action":"reject"}""").body())
            assertEquals("rejected", str(rejected, "status"))
            assertEquals(1, arr(fx.get("/api/v1/memory/proposals?status=rejected").body()).size)

            // A bad action is a 400; an unknown id is a 404.
            assertEquals(400, fx.post("/api/v1/memory/proposals/$id", """{"action":"maybe"}""").statusCode())
            assertEquals(404, fx.post("/api/v1/memory/proposals/nope", """{"action":"accept"}""").statusCode())
        }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toInt()
