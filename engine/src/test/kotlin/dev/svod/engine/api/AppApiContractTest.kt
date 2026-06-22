package dev.svod.engine.api

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleResponse
import dev.svod.engine.core.Author
import io.swagger.v3.parser.OpenAPIV3Parser
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract test: every App API response is validated against contract/openapi.yaml, and the
 * spec's declared paths are all exercised. The contract is the source of truth — this fails
 * if the server drifts from it.
 */
class AppApiContractTest {

    private val specPath: Path = Paths.get(System.getProperty("user.dir")).parent.resolve("contract/openapi.yaml")
    private val validator: OpenApiInteractionValidator =
        OpenApiInteractionValidator.createForSpecificationUrl(specPath.toUri().toString()).build()

    private fun validate(template: String, method: Request.Method, status: Int, body: String) {
        val resp = SimpleResponse.Builder(status).withContentType("application/json").withBody(body).build()
        val report = validator.validateResponse(template, method, resp)
        assertTrue(!report.hasErrors(), "contract violation on $method $template ($status): ${report.messages}")
    }

    @Test
    fun `every GET endpoint response conforms to the contract`() = runBlocking {
        ApiFixture.create().use { fx ->
            // seed content so tree/search/graph/tags are non-trivial
            fx.engine.write("vault/a.md", "---\ntags: [demo]\n---\n# A\nhello [[b]] world", null, Author("ui", "ui@svod.local"))
            fx.engine.write("vault/b.md", "# B\nbody", null, Author("ui", "ui@svod.local"))
            fx.index.waitIdle()

            val ap = "/api/v1"
            validate("/health", Request.Method.GET, 200, fx.get("/health").body())
            validate("/ready", Request.Method.GET, 200, fx.get("/ready").body())
            validate("$ap/tree", Request.Method.GET, 200, fx.get("$ap/tree").body())
            validate("$ap/file", Request.Method.GET, 200, fx.get("$ap/file?path=${ApiFixture.enc("vault/a.md")}").body())
            validate("$ap/file/history", Request.Method.GET, 200, fx.get("$ap/file/history?path=${ApiFixture.enc("vault/a.md")}").body())
            validate("$ap/file/links", Request.Method.GET, 200, fx.get("$ap/file/links?path=${ApiFixture.enc("vault/a.md")}").body())
            validate("$ap/search", Request.Method.GET, 200, fx.get("$ap/search?q=hello").body())
            // memory typing/lifecycle search params conform too
            validate("$ap/search", Request.Method.GET, 200, fx.get("$ap/search?type=policy&includeAll=true").body())
            validate("$ap/search", Request.Method.GET, 200, fx.get("$ap/search?q=hello&status=active").body())
            validate("$ap/graph", Request.Method.GET, 200, fx.get("$ap/graph").body())
            validate("$ap/tags", Request.Method.GET, 200, fx.get("$ap/tags").body())
            validate("$ap/settings", Request.Method.GET, 200, fx.get("$ap/settings").body())
            validate("$ap/index/status", Request.Method.GET, 200, fx.get("$ap/index/status").body())
            validate("$ap/metrics", Request.Method.GET, 200, fx.get("$ap/metrics").body())
            validate("$ap/conflicts", Request.Method.GET, 200, fx.get("$ap/conflicts").body())
        }
    }

    @Test
    fun `write, conflict, move and delete responses conform to the contract`() = runBlocking {
        ApiFixture.create().use { fx ->
            val ap = "/api/v1"
            // create
            val created = fx.put("$ap/file?path=${ApiFixture.enc("n/x.md")}", """{"content":"# X\nbody"}""")
            assertEquals(200, created.statusCode())
            validate("$ap/file", Request.Method.PUT, 200, created.body())

            // conflict (stale expectedRevision = null on an existing file) → 409
            val conflict = fx.put("$ap/file?path=${ApiFixture.enc("n/x.md")}", """{"content":"# X2"}""")
            assertEquals(409, conflict.statusCode())
            validate("$ap/file", Request.Method.PUT, 409, conflict.body())

            // move (link-aware) → MoveResult
            val rev = Regex("\"revision\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
            val moved = fx.post("$ap/file/move", """{"from":"n/x.md","to":"n/y.md","expectedRevision":"$rev"}""")
            assertEquals(200, moved.statusCode())
            validate("$ap/file/move", Request.Method.POST, 200, moved.body())

            // delete
            val yRev = Regex("\"revision\":\"([^\"]+)\"").find(fx.get("$ap/file?path=${ApiFixture.enc("n/y.md")}").body())!!.groupValues[1]
            val deleted = fx.delete("$ap/file?path=${ApiFixture.enc("n/y.md")}&expectedRevision=$yRev")
            assertEquals(200, deleted.statusCode())
            validate("$ap/file", Request.Method.DELETE, 200, deleted.body())
        }
    }

    @Test
    fun `conflicts expose 3-way merge data and resolve commits then clears`() = runBlocking {
        ApiFixture.create().use { fx ->
            val ap = "/api/v1"
            val author = Author("ui", "ui@svod.local")
            fx.engine.write("vault/c.md", "ours body", null, author)
            fx.conflicts.record("vault/c.md", base = "base body", ours = "ours body", theirs = "theirs body", reasons = listOf("body"))

            // GET surfaces base/ours/theirs (what a 3-way merge UI needs) and conforms to the contract.
            val listed = fx.get("$ap/conflicts")
            assertEquals(200, listed.statusCode())
            validate("$ap/conflicts", Request.Method.GET, 200, listed.body())
            assertTrue(listed.body().contains("base body") && listed.body().contains("theirs body"),
                "3-way data must be exposed: ${listed.body()}")

            // Resolve: the merged content is committed through the writer and the conflict clears.
            val resolved = fx.post("$ap/conflicts/resolve", """{"path":"vault/c.md","content":"merged body"}""")
            assertEquals(200, resolved.statusCode())
            validate("$ap/conflicts/resolve", Request.Method.POST, 200, resolved.body())

            assertTrue(fx.conflicts.isEmpty(), "conflict cleared after resolve")
            assertTrue(fx.get("$ap/conflicts").body().contains("\"conflicts\":[]"))
            val content = Regex("\"content\":\"([^\"]*)\"").find(fx.get("$ap/file?path=${ApiFixture.enc("vault/c.md")}").body())!!.groupValues[1]
            assertEquals("merged body", content)
        }
    }

    @Test
    fun `import endpoint imports an obsidian vault and conforms to the contract`() = runBlocking {
        ApiFixture.create().use { fx ->
            val ap = "/api/v1"
            val src = Files.createTempDirectory("obs-src-")
            Files.writeString(src.resolve("hello.md"), "# Hello\n[[world]]\n")
            Files.write(src.resolve("pic.bin"), byteArrayOf(1, 2, 3))

            val resp = fx.post("$ap/import", """{"source":"${src.toString().replace("\\", "\\\\")}"}""")
            assertEquals(200, resp.statusCode())
            validate("$ap/import", Request.Method.POST, 200, resp.body())
            assertTrue(resp.body().contains("hello.md") && resp.body().contains("pic.bin"), resp.body())

            // a bad source is a 400 that also conforms
            val bad = fx.post("$ap/import", """{"source":"/nonexistent/path/xyz"}""")
            assertEquals(400, bad.statusCode())
            validate("$ap/import", Request.Method.POST, 400, bad.body())
        }
    }

    @Test
    fun `ops endpoints (sync config, backup, reindex) conform to the contract`() = runBlocking {
        ApiFixture.create().use { fx ->
            val ap = "/api/v1"
            fx.engine.write("vault/a.md", "# A\nbody", null, Author("ui", "ui@svod.local"))
            fx.index.waitIdle()

            // sync/config: a standalone fixture (no peers, no backup) still conforms.
            val cfg = fx.get("$ap/sync/config")
            assertEquals(200, cfg.statusCode())
            validate("$ap/sync/config", Request.Method.GET, 200, cfg.body())

            // sync/now: no peers ⇒ 200 SyncAck with ok=false (a no-op, not an error).
            val syncNow = fx.post("$ap/sync/now", "")
            assertEquals(200, syncNow.statusCode())
            validate("$ap/sync/now", Request.Method.POST, 200, syncNow.body())
            assertTrue(syncNow.body().contains("\"ok\":false"), syncNow.body())

            // backup/now: no backup remote configured ⇒ 409 Error (not a 200, not a 500).
            val backup = fx.post("$ap/backup/now", "")
            assertEquals(409, backup.statusCode())
            validate("$ap/backup/now", Request.Method.POST, 409, backup.body())

            // settings/backup: a credential-free remote is accepted; returns the SyncConfig.
            val setOk = fx.put("$ap/settings/backup", """{"remote":"https://git.example.com/backup.git","enabled":true}""")
            assertEquals(200, setOk.statusCode())
            validate("$ap/settings/backup", Request.Method.PUT, 200, setOk.body())

            // settings/backup: an inline-credential remote is rejected (422, conforms).
            val setBad = fx.put("$ap/settings/backup", """{"remote":"https://user:secret@git.example.com/backup.git","enabled":true}""")
            assertEquals(422, setBad.statusCode())
            validate("$ap/settings/backup", Request.Method.PUT, 422, setBad.body())

            // maintenance/reindex: full HEAD reconcile ack (conforms).
            val reindex = fx.post("$ap/maintenance/reindex", "")
            assertEquals(200, reindex.statusCode())
            validate("$ap/maintenance/reindex", Request.Method.POST, 200, reindex.body())
        }
    }

    @Test
    fun `external sources endpoints register, sync and remove, conforming to the contract`() = runBlocking {
        ApiFixture.create().use { fx ->
            val ap = "/api/v1"
            // an external project dir outside the vault
            val ext = Files.createTempDirectory("ext-proj-")
            Files.writeString(ext.resolve("note.md"), "# Note\nfrom another project\n")
            val extJson = ext.toString().replace("\\", "\\\\")

            // list: empty, conforms
            val empty = fx.get("$ap/sources")
            assertEquals(200, empty.statusCode())
            validate("$ap/sources", Request.Method.GET, 200, empty.body())

            // register
            val reg = fx.post("$ap/sources", """{"path":"$extJson","into":"projects/demo"}""")
            assertEquals(200, reg.statusCode())
            validate("$ap/sources", Request.Method.POST, 200, reg.body())
            val id = Regex("\"id\":\"([^\"]+)\"").find(reg.body())!!.groupValues[1]

            // a relative path is rejected (422, conforms)
            val badReg = fx.post("$ap/sources", """{"path":"relative/path"}""")
            assertEquals(422, badReg.statusCode())
            validate("$ap/sources", Request.Method.POST, 422, badReg.body())

            // sync the one source → note.md created under the prefix
            val synced = fx.post("$ap/sources/$id/sync", "")
            assertEquals(200, synced.statusCode())
            validate("$ap/sources/{id}/sync", Request.Method.POST, 200, synced.body())
            assertTrue(synced.body().contains("projects/demo/note.md"), synced.body())

            // sync-all conforms too
            val syncAll = fx.post("$ap/sources/sync", "")
            assertEquals(200, syncAll.statusCode())
            validate("$ap/sources/sync", Request.Method.POST, 200, syncAll.body())

            // PATCH toggles a setting and conforms; the response carries autoSync + watching.
            val patched = fx.patch("$ap/sources/$id", """{"autoSync":true}""")
            assertEquals(200, patched.statusCode())
            validate("$ap/sources/{id}", Request.Method.PATCH, 200, patched.body())
            assertTrue(patched.body().contains("\"autoSync\":true"), patched.body())

            // remove → 204
            val removed = fx.delete("$ap/sources/$id")
            assertEquals(204, removed.statusCode())
        }
    }

    @Test
    fun `embedder + index control endpoints conform to the contract`() = runBlocking {
        ApiFixture.create().use { fx ->
            val ap = "/api/v1"
            // index control: pause / resume / reembed all return the IndexStatus shape.
            for (op in listOf("pause", "resume", "reembed")) {
                val r = fx.post("$ap/index/$op", "")
                assertEquals(200, r.statusCode(), op)
                validate("$ap/index/$op", Request.Method.POST, 200, r.body())
            }
            // index/status now carries keywordReady + embedding (extended schema).
            val status = fx.get("$ap/index/status")
            assertTrue(status.body().contains("keywordReady") && status.body().contains("embedding"), status.body())

            // No embedder control wired in this fixture ⇒ 501, conforming to the Error schema.
            val put = fx.put("$ap/embedder", """{"provider":"none"}""")
            assertEquals(501, put.statusCode())
            validate("$ap/embedder", Request.Method.PUT, 501, put.body())
            val test = fx.post("$ap/embedder/test", """{"provider":"none"}""")
            assertEquals(501, test.statusCode())
            validate("$ap/embedder/test", Request.Method.POST, 501, test.body())

            // No embedder control wired ⇒ 501; and the 200 EmbedderModels shape conforms to the schema.
            val models = fx.post("$ap/embedder/models", """{"provider":"none"}""")
            assertEquals(501, models.statusCode())
            validate("$ap/embedder/models", Request.Method.POST, 501, models.body())
            validate(
                "$ap/embedder/models", Request.Method.POST, 200,
                """{"provider":"local-ollama","models":[{"id":"bge-m3","dimension":1024},{"id":"nomic-embed-text"}]}""",
            )
        }
    }

    @Test
    fun `every path declared in the contract is implemented`() {
        val openApi = OpenAPIV3Parser().read(specPath.toString())
        val declared = openApi.paths.keys.toSet()
        val implemented = setOf(
            "/health", "/ready", "/api/v1/tree", "/api/v1/file", "/api/v1/file/move", "/api/v1/file/restore",
            "/api/v1/file/history", "/api/v1/file/diff", "/api/v1/file/revision", "/api/v1/file/links",
            "/api/v1/search", "/api/v1/graph", "/api/v1/tags", "/api/v1/settings", "/api/v1/index/status",
            "/api/v1/vaults", "/api/v1/vaults/{id}", "/api/v1/metrics", "/api/v1/conflicts", "/api/v1/conflicts/resolve",
            "/api/v1/import", "/api/v1/events",
            "/api/v1/sync/config", "/api/v1/sync/now", "/api/v1/backup/now",
            "/api/v1/settings/backup", "/api/v1/maintenance/reindex",
            "/api/v1/sources", "/api/v1/sources/{id}", "/api/v1/sources/{id}/sync", "/api/v1/sources/sync",
            "/api/v1/embedder", "/api/v1/embedder/test", "/api/v1/embedder/models",
            "/api/v1/index/reembed", "/api/v1/index/pause", "/api/v1/index/resume",
        )
        assertEquals(declared, implemented, "contract paths and implemented routes must match exactly")
        assertTrue(Files.exists(specPath), "contract file present")
    }
}
