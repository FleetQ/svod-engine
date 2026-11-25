package dev.svod.engine.mcp

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0020 Decision 2 over real MCP HTTP: roles per grant, enforced in the routed() resolver. */
class McpGrantRolesTest {
    private suspend fun connect(port: Int, token: String): Client {
        val http = HttpClient(ClientCIO) {
            install(SSE)
            install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
        }
        val client = Client(Implementation(name = "svod-roles-test", version = "1.0.0"))
        client.connect(StreamableHttpClientTransport(client = http, url = "http://127.0.0.1:$port/mcp"))
        return client
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.text(): String =
        (content.firstOrNull { it is TextContent } as TextContent).text!!

    /** How a call to [tool] fails, with the tool name blanked so two names can be compared. */
    private suspend fun Client.outcome(tool: String): String = runCatching { callTool(tool, emptyMap<String, Any?>()) }.fold(
        { "result isError=${it.isError} ${(it.content.firstOrNull() as? TextContent)?.text?.replace(tool, "<tool>")}" },
        { "error ${it::class.simpleName} ${it.message?.replace(tool, "<tool>")}" },
    )

    private fun spec(token: String, role: AgentRole = AgentRole.WRITE, vaults: List<String> = listOf("work"),
                     user: String? = null, roles: Map<String, GrantRole> = emptyMap()) =
        AgentRegistry.AgentSpec(token, token, role, vaults = vaults, userId = user, grantRoles = roles)

    private fun run(specs: List<AgentRegistry.AgentSpec>, body: suspend (port: Int) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("roles-work-")
        val engine = SvodEngine.open(dir, scope)
        val index = IndexService(dir, dir.resolve(".svod/index"), NoneEmbedder).start()
        val tools = SvodTools(engine, index, AuditLog(dir.resolve(".svod/audit/a.log")), RateLimiter.default())
        val server = SvodMcpServer({ vid -> if (vid == "work") tools else null }, "work", AgentRegistry(specs)).start(0)
        try { body(server.port) } finally { server.stop(); index.close(); engine.close() }
    }

    private suspend fun Client.ok(tool: String, args: Map<String, Any?>) {
        val r = callTool(tool, args)
        assertTrue(r.isError != true && !r.text().contains("denied") && !r.text().contains("conflict") && !r.text().contains("not_found"), "$tool should be allowed: ${r.text()}")
    }

    private suspend fun Client.mayNot(tool: String, args: Map<String, Any?>, role: String, id: String) {
        val r = callTool(tool, args)
        assertEquals(true, r.isError, "$tool should be denied: ${r.text()}")
        assertTrue(r.text().contains("agent '$id' (role $role in vault 'work') may not call '$tool'"), r.text())
    }

    private suspend fun Client.rev(path: String): String =
        Regex("\"revision\":\"([0-9a-f]+)\"").find(callTool("read", mapOf("path" to path)).text())!!.groupValues[1]

    private suspend fun Client.promoteArgs() = mapOf("from" to "messy/d.md", "to" to "d.md", "expectedRevision" to rev("messy/d.md"))

    private val mutations = mapOf(
        "write" to mapOf("path" to "a.md", "content" to "x"),
        "edit" to mapOf("path" to "a.md", "oldString" to "x", "newString" to "y"),
        "move" to mapOf("from" to "a.md", "to" to "b.md"),
        "delete" to mapOf("path" to "a.md"),
        "remember" to mapOf("content" to "a fact"),
        "promote" to mapOf("from" to "messy/d.md", "to" to "d.md"),
    )

    @Test
    fun `reader is denied every mutation and allowed every read`() = run(listOf(spec("rd", roles = mapOf("work" to GrantRole.READER)))) { port ->
        val c = connect(port, "rd")
        try {
            mutations.forEach { (t, a) -> c.mayNot(t, a, "reader", "rd") }
            c.ok("list", emptyMap()); c.ok("tree", emptyMap()); c.ok("search", mapOf("query" to "x"))
            c.ok("grep", mapOf("pattern" to "x")); c.ok("context_pack", mapOf("query" to "x"))
            c.ok("graph_communities", emptyMap()); c.ok("graph_status", emptyMap())
        } finally { c.close() }
    }

    @Test
    fun `reader reads a note and its history diff revision links`() = run(listOf(
        spec("wr", roles = mapOf("work" to GrantRole.WRITER)), spec("rd", roles = mapOf("work" to GrantRole.READER)),
    )) { port ->
        val w = connect(port, "wr")
        try { w.ok("write", mapOf("path" to "n.md", "content" to "# n [[m]]")) } finally { w.close() }
        val c = connect(port, "rd")
        try {
            val rev = Regex("\"commit\":\"([0-9a-f]+)\"").find(c.callTool("history", mapOf("path" to "n.md")).text())!!.groupValues[1]
            c.ok("history", mapOf("path" to "n.md"))
            c.ok("get_revision", mapOf("path" to "n.md", "revision" to rev))
            c.ok("diff", mapOf("path" to "n.md", "from" to rev, "to" to rev))
            c.ok("link", mapOf("path" to "n.md"))
            c.ok("graph_query", mapOf("path" to "n.md"))
            // an unknown community id is a not_found from the tool, which proves the role check let it through
            assertTrue(c.callTool("graph_community", mapOf("id" to "none")).text().contains("not_found"))
        } finally { c.close() }
    }

    @Test
    fun `writer writes edits moves deletes remembers but cannot promote`() = run(listOf(spec("wr", roles = mapOf("work" to GrantRole.WRITER)))) { port ->
        val c = connect(port, "wr")
        try {
            c.ok("write", mapOf("path" to "a.md", "content" to "x"))
            c.ok("edit", mapOf("path" to "a.md", "oldString" to "x", "newString" to "y"))
            c.ok("move", mapOf("from" to "a.md", "to" to "b.md", "expectedRevision" to c.rev("a.md")))
            c.ok("delete", mapOf("path" to "b.md", "expectedRevision" to c.rev("b.md")))
            c.ok("remember", mapOf("content" to "a durable fact about roles"))
            c.ok("write", mapOf("path" to "messy/d.md", "content" to "draft"))
            c.mayNot("promote", mutations.getValue("promote"), "writer", "wr")
        } finally { c.close() }
    }

    @Test
    fun `maintainer may promote`() = run(listOf(spec("mt", roles = mapOf("work" to GrantRole.MAINTAINER)))) { port ->
        val c = connect(port, "mt")
        try {
            c.ok("write", mapOf("path" to "messy/d.md", "content" to "draft"))
            c.ok("promote", c.promoteArgs())
            c.ok("read", mapOf("path" to "d.md"))
        } finally { c.close() }
    }

    @Test
    fun `bare grant on a user-bound agent is writer`() = run(listOf(spec("bound", user = "ann"))) { port ->
        val c = connect(port, "bound")
        try {
            c.ok("write", mapOf("path" to "messy/d.md", "content" to "draft"))
            c.mayNot("promote", mutations.getValue("promote"), "writer", "bound")
        } finally { c.close() }
    }

    @Test
    fun `READ_ONLY ceiling wins over a maintainer grant`() = run(listOf(spec("ro", role = AgentRole.READ_ONLY, roles = mapOf("work" to GrantRole.MAINTAINER)))) { port ->
        val c = connect(port, "ro")
        try {
            val names = c.listTools().tools.map { it.name }.toSet()
            assertTrue("write" !in names && "promote" !in names, "READ_ONLY must not see write or promote: $names")
            val unknown = c.outcome("no_such_tool")
            assertEquals(unknown, c.outcome("write"), "hidden write fails like an unknown tool")
            assertEquals(unknown, c.outcome("promote"), "hidden promote fails like an unknown tool")
            c.ok("list", emptyMap())
        } finally { c.close() }
    }

    @Test
    fun `an agent without user or role grants still promotes`() = run(listOf(spec("legacy"))) { port ->
        val c = connect(port, "legacy")
        try {
            c.ok("write", mapOf("path" to "messy/d.md", "content" to "draft"))
            c.ok("promote", c.promoteArgs())
        } finally { c.close() }
    }

    @Test
    fun `an ungranted vault is denied as before for both kinds of agent`() = run(listOf(
        spec("legacy"), spec("mt", roles = mapOf("work" to GrantRole.MAINTAINER)),
    )) { port ->
        for (tok in listOf("legacy", "mt")) {
            val c = connect(port, tok)
            try {
                val r = c.callTool("read", mapOf("path" to "x.md", "vault" to "other"))
                assertEquals(true, r.isError)
                assertTrue(r.text().contains("vault 'other' is not granted to agent '$tok'"), r.text())
            } finally { c.close() }
        }
    }

    @Test
    fun `every registered tool is in the role table`() = run(listOf(spec("mt", roles = mapOf("work" to GrantRole.MAINTAINER)))) { port ->
        val c = connect(port, "mt")
        try {
            val names = c.listTools().tools.map { it.name }.toSet()
            assertEquals(GrantResolver.REQUIRED.keys, names)
        } finally { c.close() }
    }
}
