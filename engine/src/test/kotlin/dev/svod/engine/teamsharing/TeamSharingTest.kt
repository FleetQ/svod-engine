package dev.svod.engine.teamsharing

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR-0020 multi-user integration: a real engine (`SvodNode`), one team vault, three people, six agents,
 * MCP over self-signed HTTPS. Core checks always run; the scaled run is in [TeamSharingHeavyTest].
 */
class TeamSharingTest {

    @Test
    fun `concurrent people - no lost write, conflicts surfaced, attribution exact`(): Unit = runBlocking {
        TeamFixture().use { fx ->
            val report = runConcurrentScenario(fx, notesPerAgent = 20, sharedEditsPerAgent = 10)
            println("TEAM-SHARING core: $report")
            writeReport("core", report)
        }
    }

    private val actions = listOf("read", "search", "write", "edit", "move", "delete", "remember", "promote")

    private fun allowed(a: TestAgent, action: String) = when (action) {
        "read", "search" -> true
        "promote" -> a.mayPromote
        else -> a.mayWrite
    }

    @Test
    fun `role matrix over TLS MCP - every agent against every action`(): Unit = runBlocking {
        TeamFixture().use { fx ->
            val seeder = fx.connect(fx.agents.first { it.id == "alice-1" })
            val clients = fx.agents.associateWith { fx.connect(it) }
            try {
                // each agent gets its own notes, so allowed mutations never interfere with one another
                suspend fun seed(path: String, content: String): String {
                    assertEquals("ok", seeder.call("write", mapOf("path" to path, "content" to content)).status, path)
                    return seeder.call("read", mapOf("path" to path)).str("revision")!!
                }
                val revs = HashMap<String, String>()
                for (a in fx.agents) {
                    seed("matrix/${a.id}/a.md", "alpha ${a.id}")
                    seed("matrix/${a.id}/e.md", "alpha-e ${a.id}")
                    revs["${a.id}/mv"] = seed("matrix/${a.id}/mv.md", "to move ${a.id}")
                    revs["${a.id}/del"] = seed("matrix/${a.id}/del.md", "to delete ${a.id}")
                    revs["${a.id}/draft"] = seed("messy/${a.id}.md", "draft ${a.id}")
                }

                fun args(a: TestAgent, action: String): Map<String, Any?> = when (action) {
                    "read" -> mapOf("path" to "matrix/${a.id}/a.md")
                    "search" -> mapOf("query" to "alpha ${a.id}")
                    "write" -> mapOf("path" to "matrix/${a.id}/w.md", "content" to "written by ${a.id}")
                    "edit" -> mapOf("path" to "matrix/${a.id}/e.md", "oldString" to "alpha-e", "newString" to "beta-e")
                    "move" -> mapOf("from" to "matrix/${a.id}/mv.md", "to" to "matrix/${a.id}/mv2.md", "expectedRevision" to revs.getValue("${a.id}/mv"))
                    "delete" -> mapOf("path" to "matrix/${a.id}/del.md", "expectedRevision" to revs.getValue("${a.id}/del"))
                    "remember" -> mapOf("content" to "The matrix fact recorded by ${a.id} is unique-${a.id}-zq.")
                    "promote" -> mapOf("from" to "messy/${a.id}.md", "to" to "kept/${a.id}.md", "expectedRevision" to revs.getValue("${a.id}/draft"))
                    else -> error(action)
                }

                val mutating = setOf("write", "edit", "move", "delete", "promote")
                for ((a, c) in clients) for (action in actions) {
                    val before = fx.head()
                    val r = c.call(action, args(a, action))
                    val label = "${a.id} (${a.effective}) $action: ${r.text}"
                    if (allowed(a, action)) {
                        assertEquals(false, r.isError, label)
                        assertTrue(r.status in setOf("ok", "written"), label)
                        if (action in mutating) assertTrue(fx.head() != before, "HEAD must move: $label")
                    } else {
                        assertEquals(true, r.isError, label)
                        assertEquals("denied", r.status, label)
                        assertTrue(r.str("message")!!.contains("may not call '$action'") && r.str("message")!!.contains("role ${a.effective}"), label)
                        assertEquals(before, fx.head(), "a denied call must change nothing: $label")
                    }
                }

                // denied agents left every note exactly as seeded
                for (a in fx.agents.filter { !it.mayWrite }) {
                    assertEquals("alpha-e ${a.id}", fx.node.engine.read("matrix/${a.id}/e.md")?.text)
                    assertNotNull(fx.node.engine.read("matrix/${a.id}/mv.md"))
                    assertNotNull(fx.node.engine.read("matrix/${a.id}/del.md"))
                    assertNotNull(fx.node.engine.read("messy/${a.id}.md"))
                    assertEquals(null, fx.node.engine.read("matrix/${a.id}/w.md"))
                }
                // writers could not promote, maintainers could
                for (a in fx.agents.filter { it.mayWrite }) {
                    assertEquals(a.mayPromote, fx.node.engine.read("kept/${a.id}.md") != null, "promote result for ${a.id}")
                }
                assertEquals(0, fx.node.engine.queueDepth())
            } finally {
                clients.values.forEach { it.close() }
                seeder.close()
            }
        }
    }

    @Test
    fun `MCP without a token or with an unknown token is 401`(): Unit = runBlocking {
        TeamFixture().use { fx ->
            val http = HttpClient(ClientCIO) { engine { https { trustManager = trustAll } } }
            try {
                val url = "https://127.0.0.1:${fx.port}/mcp"
                val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
                val none = http.post(url) { contentType(ContentType.Application.Json); setBody(body) }
                assertEquals(HttpStatusCode.Unauthorized, none.status, "no token")
                val unknown = http.post(url) {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer not-a-real-token")
                    setBody(body)
                }
                assertEquals(HttpStatusCode.Unauthorized, unknown.status, "unknown token")
            } finally { http.close() }
        }
    }
}

internal fun writeReport(name: String, report: Report) {
    runCatching {
        val dir = Path.of("build", "team-sharing")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$name.txt"), report.toString() + "\n")
    }
}
