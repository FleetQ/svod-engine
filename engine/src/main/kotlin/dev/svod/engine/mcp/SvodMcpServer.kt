package dev.svod.engine.mcp

import dev.svod.engine.index.SearchFilters
import dev.svod.engine.index.SearchMode
import dev.svod.engine.index.SearchQuery
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * MCP server over streamable HTTP (Ktor). Binds **loopback only**; remote agents reach it
 * through a TLS-terminating front (Step 5) with the same per-agent bearer token. Each
 * authenticated session gets its own MCP [Server] bound to the resolved [AgentIdentity], so
 * every tool call carries the right identity — which becomes the git commit author.
 */
class SvodMcpServer(
    private val tools: SvodTools,
    private val registry: AgentRegistry,
    private val host: String = "127.0.0.1",
) {
    class Running(val embedded: EmbeddedServer<*, *>, val port: Int) {
        fun stop() = embedded.stop(500, 1000)
    }

    fun start(requestedPort: Int = 0): Running {
        val embedded = embeddedServer(CIO, host = host, port = requestedPort) { configure() }
        embedded.start(wait = false)
        val port = runBlocking { embedded.engine.resolvedConnectors().first().port }
        return Running(embedded, port)
    }

    private fun Application.configure() {
        install(SSE)
        install(ContentNegotiation) { json(McpJson) }
        install(Authentication) {
            bearer("mcp") {
                authenticate { credential ->
                    registry.authenticate(credential.token)?.let { UserIdPrincipal(it.agentId) }
                }
            }
        }

        val transports = ConcurrentMap<String, StreamableHttpServerTransport>()

        routing {
            authenticate("mcp") {
                route("/mcp") {
                    sse {
                        val transport = findTransport(call, transports) ?: return@sse
                        transport.handleRequest(this, call)
                    }
                    post {
                        val transport = getOrCreateTransport(call, transports) ?: return@post
                        transport.handleRequest(null, call)
                    }
                    delete {
                        val transport = findTransport(call, transports) ?: return@delete
                        transport.handleRequest(null, call)
                    }
                }
            }
        }
    }

    private suspend fun findTransport(call: ApplicationCall, transports: ConcurrentMap<String, StreamableHttpServerTransport>): StreamableHttpServerTransport? {
        val sessionId = call.request.header(SESSION_HEADER)
        if (sessionId.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "No valid session ID")
            return null
        }
        val transport = transports[sessionId]
        if (transport == null) call.respond(HttpStatusCode.NotFound, "Session not found")
        return transport
    }

    private suspend fun getOrCreateTransport(call: ApplicationCall, transports: ConcurrentMap<String, StreamableHttpServerTransport>): StreamableHttpServerTransport? {
        val sessionId = call.request.header(SESSION_HEADER)
        if (sessionId != null) {
            val transport = transports[sessionId]
            if (transport == null) call.respond(HttpStatusCode.NotFound, "Session not found")
            return transport
        }

        val agentId = call.principal<UserIdPrincipal>()?.name
        val agent = agentId?.let { registry.byAgentId(it) }
        if (agent == null) {
            call.respond(HttpStatusCode.Unauthorized, "Unknown agent")
            return null
        }

        val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true))
        transport.setOnSessionInitialized { id -> transports[id] = transport }
        transport.setOnSessionClosed { id -> transports.remove(id) }

        val server = buildServer(agent)
        server.onClose { transport.sessionId?.let { transports.remove(it) } }
        server.createSession(transport)
        return transport
    }

    /** A fresh MCP server whose every tool handler is bound to [agent]. */
    private fun buildServer(agent: AgentIdentity): Server {
        val server = Server(
            Implementation(name = "svod", version = "0.1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )

        fun schema(props: Map<String, String>, required: List<String>) = ToolSchema(
            properties = buildJsonObject {
                props.forEach { (name, type) -> putJsonObject(name) { put("type", type) } }
            },
            required = required,
        )

        server.addTool("read", "Read a note's current content + revision.", schema(mapOf("path" to "string"), listOf("path"))) { req ->
            tools.read(agent, req.str("path")!!).toCallToolResult()
        }
        server.addTool("write", "Create or update a note (optimistic via expectedRevision).", schema(mapOf("path" to "string", "content" to "string", "expectedRevision" to "string"), listOf("path", "content"))) { req ->
            tools.write(agent, req.str("path")!!, req.str("content") ?: "", req.str("expectedRevision")).toCallToolResult()
        }
        server.addTool("delete", "Soft-delete a note to .trash/.", schema(mapOf("path" to "string", "expectedRevision" to "string"), listOf("path"))) { req ->
            tools.delete(agent, req.str("path")!!, req.str("expectedRevision")).toCallToolResult()
        }
        server.addTool("move", "Move/rename a note.", schema(mapOf("from" to "string", "to" to "string", "expectedRevision" to "string"), listOf("from", "to"))) { req ->
            tools.move(agent, req.str("from")!!, req.str("to")!!, req.str("expectedRevision")).toCallToolResult()
        }
        server.addTool("promote", "Promote a draft from messy/ into the curated vault.", schema(mapOf("from" to "string", "to" to "string", "expectedRevision" to "string"), listOf("from", "to"))) { req ->
            tools.promote(agent, req.str("from")!!, req.str("to")!!, req.str("expectedRevision")).toCallToolResult()
        }
        server.addTool("search", "Hybrid search (keyword/semantic/hybrid) with filters.", schema(mapOf("query" to "string", "mode" to "string", "limit" to "integer"), listOf("query"))) { req ->
            tools.search(agent, req.toSearchQuery()).toCallToolResult()
        }
        server.addTool("list", "List note paths (optionally filtered by prefix).", schema(mapOf("pathPrefix" to "string"), emptyList())) { req ->
            tools.list(agent, req.str("pathPrefix")).toCallToolResult()
        }
        server.addTool("history", "Commit history for a note.", schema(mapOf("path" to "string", "max" to "integer"), listOf("path"))) { req ->
            tools.history(agent, req.str("path")!!, req.int("max", 50)).toCallToolResult()
        }
        server.addTool("diff", "Unified diff of a note between two revisions.", schema(mapOf("path" to "string", "from" to "string", "to" to "string"), listOf("path", "from", "to"))) { req ->
            tools.diff(agent, req.str("path")!!, req.str("from")!!, req.str("to")!!).toCallToolResult()
        }
        server.addTool("get_revision", "Read a note's content at a specific revision.", schema(mapOf("path" to "string", "revision" to "string"), listOf("path", "revision"))) { req ->
            tools.getRevision(agent, req.str("path")!!, req.str("revision")!!).toCallToolResult()
        }
        server.addTool("link", "Outgoing wikilinks for a note (resolved + unresolved).", schema(mapOf("path" to "string"), listOf("path"))) { req ->
            tools.link(agent, req.str("path")!!).toCallToolResult()
        }
        server.addTool("graph_query", "1-hop link neighborhood (outlinks + backlinks).", schema(mapOf("path" to "string"), listOf("path"))) { req ->
            tools.graphQuery(agent, req.str("path")!!).toCallToolResult()
        }
        return server
    }

    companion object {
        private const val SESSION_HEADER = "mcp-session-id"
    }
}

// ---- request/result adapters (MCP wire ↔ domain) ----

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.str(key: String): String? {
    val element = arguments?.get(key) ?: return null
    if (element is kotlinx.serialization.json.JsonNull) return null // explicit JSON null, not the string "null"
    return element.jsonPrimitive.content.takeIf { it.isNotEmpty() }
}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.int(key: String, default: Int): Int =
    runCatching { arguments?.get(key)?.jsonPrimitive?.int }.getOrNull() ?: default

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.toSearchQuery(): SearchQuery {
    val text = str("query") ?: ""
    val mode = when (str("mode")?.lowercase()) {
        "keyword" -> SearchMode.KEYWORD
        "semantic" -> SearchMode.SEMANTIC
        else -> SearchMode.HYBRID
    }
    val limit = int("limit", 10)
    val filtersObj = arguments?.get("filters") as? JsonObject
    val filters = if (filtersObj == null) SearchFilters() else SearchFilters(
        tags = (filtersObj["tags"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
        pathPrefix = filtersObj["pathPrefix"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() },
        createdFrom = runCatching { filtersObj["createdFrom"]?.jsonPrimitive?.long }.getOrNull(),
        createdTo = runCatching { filtersObj["createdTo"]?.jsonPrimitive?.long }.getOrNull(),
    )
    return SearchQuery(text, filters, mode, limit)
}

private val RESULT_JSON = kotlinx.serialization.json.Json { encodeDefaults = true }

private fun ToolResult.toCallToolResult(): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(RESULT_JSON.encodeToString(JsonObject.serializer(), data))),
        isError = isError,
    )
