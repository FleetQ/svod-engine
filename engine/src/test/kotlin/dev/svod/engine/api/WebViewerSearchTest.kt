package dev.svod.engine.api

import dev.svod.engine.api.ApiFixture.Companion.enc
import dev.svod.engine.core.Author
import dev.svod.engine.index.NoneEmbedder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reference viewer's search pane (examples/web-viewer). There is no JS test runner by design
 * (ADR-0008: dependency-free viewer), so this serves the REAL viewer folder through the engine and
 * checks (1) the markup the script binds to, (2) that the script calls the endpoints with the
 * fields the API really uses, and (3) that the API answers those exact calls with those fields.
 */
class WebViewerSearchTest {

    private val UI = Author("ui", "ui@svod.local")
    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun viewerDir(): Path {
        val projectDir = System.getProperty("svod.projectDir")
        assertNotNull(projectDir, "the svod.projectDir system property must be set by engine/build.gradle.kts")
        val dir = Paths.get(projectDir, "..", "examples", "web-viewer").normalize()
        assertTrue(Files.isDirectory(dir), "the reference viewer folder must exist at $dir")
        return dir
    }

    /** Temp vault + engine + index (fed by commits) + App API serving the real viewer folder. */
    private fun serve(): ApiFixture =
        ApiFixture(NoneEmbedder, apiConfig = AppApiServer.Config(webViewerPath = viewerDir().toString()))

    @Test
    fun `the real viewer ships a search pane`() {
        serve().use { fx ->
            val root = fx.get("/")
            assertEquals(200, root.statusCode())
            val html = root.body()
            val required = listOf(
                // search pane
                "searchForm", "searchQuery", "searchMode", "searchTags", "searchPath", "searchStatus", "searchResults",
                // note view
                "note", "notePath", "noteMeta", "noteContent", "noteHistory",
                // existing ids the script still binds to
                "feed", "feedEmpty", "diff", "diffPath", "indexInfo", "endpoint", "status", "offline", "offlineUrl", "retry",
            )
            for (id in required) assertTrue(html.contains("id=\"$id\""), "index.html must contain id=\"$id\"")
            for (mode in listOf("hybrid", "keyword", "semantic")) {
                assertTrue(html.contains("value=\"$mode\""), "the mode selector must offer $mode")
            }
            assertTrue(html.contains("class=\"search-pane\""), "the search pane section is present")
        }
    }

    @Test
    fun `viewer script calls the search, file and history endpoints with the right fields`() {
        serve().use { fx ->
            val r = fx.get("/app.js")
            assertEquals(200, r.statusCode())
            val js = r.body()
            for (needle in listOf(
                "/api/v1/search", "mode=", "pathPrefix=", "tags=", "matchedKeyword", "matchedSemantic", "degraded",
                "/api/v1/file?path=", "/api/v1/file/history?path=", "epochSeconds", "lastModifiedBy",
                "/api/v1/file/diff", "/api/v1/file/revision", "showDiff", "openNote", "runSearch",
                "&quot;", "&#39;",
            )) assertTrue(js.contains(needle), "app.js must contain '$needle'")
        }
    }

    @Test
    fun `the API answers the viewer's search and open-note calls with the fields the viewer reads`() = runBlocking {
        serve().use { fx ->
            fx.engine.write("projects/alpha.md", "---\ntags: [deploy]\n---\n# Alpha\nThe rollout checklist for alpha.", null, UI)
            fx.engine.write("projects/beta.md", "---\ntags: [deploy, infra]\n---\n# Beta\nThe rollout checklist for beta.", null, UI)
            fx.engine.write("notes/gamma.md", "# Gamma\nThe rollout checklist without tags.", null, UI)
            fx.index.waitIdle()

            // The viewer's keyword query: q, mode, limit
            val kw = fx.get("/api/v1/search?q=rollout&mode=keyword&limit=20")
            assertEquals(200, kw.statusCode(), kw.body())
            val kwJson = json(kw.body())
            // `degraded` may be left out when empty; the viewer reads it as `j.degraded || []`.
            assertTrue(kwJson.containsKey("mode"), "result carries the mode: $kwJson")
            val hits = kwJson["hits"]!!.jsonArray.map { it.jsonObject }
            assertEquals(setOf("projects/alpha.md", "projects/beta.md", "notes/gamma.md"), hits.map { it["path"]!!.jsonPrimitive.content }.toSet())
            for (h in hits) {
                for (f in listOf("path", "heading", "snippet", "matchedKeyword", "matchedSemantic", "tags")) {
                    assertTrue(h.containsKey(f), "hit must carry '$f': $h")
                }
                assertEquals("true", h["matchedKeyword"]!!.jsonPrimitive.content, "a keyword hit is badged keyword")
            }

            // Tag filter, one `tags` param per tag, as the viewer sends it
            val tagged = fx.get("/api/v1/search?q=rollout&mode=keyword&limit=20&tags=deploy&tags=infra")
            assertEquals(200, tagged.statusCode())
            assertEquals(setOf("projects/beta.md"), json(tagged.body())["hits"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }.toSet())

            // Filter-only: a tag and no query (the viewer leaves q out when blank)
            val tagOnly = fx.get("/api/v1/search?mode=hybrid&limit=20&tags=deploy")
            assertEquals(200, tagOnly.statusCode())
            assertEquals(setOf("projects/alpha.md", "projects/beta.md"), json(tagOnly.body())["hits"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }.toSet())

            // Path prefix filter
            val prefixed = fx.get("/api/v1/search?q=rollout&mode=keyword&limit=20&pathPrefix=${enc("notes/")}")
            assertEquals(200, prefixed.statusCode())
            assertEquals(setOf("notes/gamma.md"), json(prefixed.body())["hits"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }.toSet())

            // Neither query nor filter: an error with a message the viewer shows
            val bad = fx.get("/api/v1/search?mode=hybrid&limit=20")
            assertEquals(400, bad.statusCode())
            assertTrue(json(bad.body()).containsKey("message"), bad.body())

            // Open note: content + lastModifiedBy
            val path = "projects/beta.md"
            val file = fx.get("/api/v1/file?path=${enc(path)}")
            assertEquals(200, file.statusCode(), file.body())
            val fileJson = json(file.body())
            assertTrue(fileJson["content"]!!.jsonPrimitive.content.contains("rollout checklist for beta"), "note content: $fileJson")
            assertTrue(fileJson.containsKey("lastModifiedBy"), "the viewer shows who last modified the note: $fileJson")

            // History: commits with commit, author, epochSeconds, message
            val hist = fx.get("/api/v1/file/history?path=${enc(path)}&max=50")
            assertEquals(200, hist.statusCode())
            val commits = json(hist.body())["commits"]!!.jsonArray.map { it.jsonObject }
            assertTrue(commits.isNotEmpty(), "a written note has history")
            for (f in listOf("commit", "author", "epochSeconds", "message")) assertTrue(commits[0].containsKey(f), "commit must carry '$f'")

            // The viewer's fallback for a commit whose diff is unavailable: the content at that revision
            val commit = commits[0]["commit"]!!.jsonPrimitive.content
            val rev = fx.get("/api/v1/file/revision?path=${enc(path)}&revision=${enc(commit)}")
            assertEquals(200, rev.statusCode())
            assertTrue(json(rev.body())["content"]!!.jsonPrimitive.content.contains("rollout checklist for beta"), "revision: ${rev.body()}")

            // After an edit, the newest commit's diff against its parent answers with `diff`
            val currentRev = fileJson["revision"]!!.jsonPrimitive.content
            val edit = fx.put(
                "/api/v1/file?path=${enc(path)}",
                """{"content":"---\ntags: [deploy, infra]\n---\n# Beta\nThe rollout checklist for beta, revised.","expectedRevision":"$currentRev"}""",
            )
            assertEquals(200, edit.statusCode(), edit.body())
            val newest = json(fx.get("/api/v1/file/history?path=${enc(path)}&max=50").body())["commits"]!!.jsonArray[0].jsonObject["commit"]!!.jsonPrimitive.content
            val diff = fx.get("/api/v1/file/diff?path=${enc(path)}&from=${enc("$newest~1")}&to=${enc(newest)}")
            assertEquals(200, diff.statusCode(), diff.body())
            assertTrue(json(diff.body())["diff"]!!.jsonPrimitive.content.contains("revised"), "diff: ${diff.body()}")
        }
    }
}
