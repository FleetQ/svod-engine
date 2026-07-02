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
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
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
    /** Resolves the SvodTools for a vault id (multi-vault). Null ⇒ unknown vault. */
    private val toolsByVault: (String) -> SvodTools?,
    /** The vault a call targets when it names none (and the agent grants none explicitly). */
    private val defaultVaultId: String,
    private val registry: AgentRegistry,
    private val host: String = "127.0.0.1",
) {
    /** Single-vault convenience: one tool set for every vault id. */
    constructor(tools: SvodTools, registry: AgentRegistry, host: String = "127.0.0.1")
        : this({ _ -> tools }, "default", registry, host)

    class Running(val embedded: EmbeddedServer<*, *>, val port: Int) {
        fun stop() = embedded.stop(500, 1000)
    }

    /** TLS material for serving MCP over HTTPS (remote agents reach MCP encrypted). */
    data class Tls(
        val keyStore: java.security.KeyStore,
        val keyAlias: String,
        val keyStorePassword: CharArray,
        val privateKeyPassword: CharArray,
    )

    fun start(requestedPort: Int = 0, tls: Tls? = null): Running {
        val h = host
        val embedded = embeddedServer(Netty, configure = {
            if (tls == null) {
                connector { host = h; port = requestedPort }
            } else {
                sslConnector(tls.keyStore, tls.keyAlias, { tls.keyStorePassword }, { tls.privateKeyPassword }) {
                    host = h; port = requestedPort
                }
            }
        }) { configure() }
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

    /** A fresh MCP server for [agent]; each call selects its target vault (default = the agent's). */
    private fun buildServer(agent: AgentIdentity): Server {
        val server = Server(
            Implementation(name = "svod", version = "0.1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )

        // Every per-vault tool's schema gets an optional `vault` (default = the agent's vault). A
        // call may target any vault the agent is granted; an ungranted/unknown vault is rejected.
        fun schema(props: Map<String, String>, required: List<String>) = ToolSchema(
            properties = buildJsonObject {
                props.forEach { (name, type) -> putJsonObject(name) { put("type", type) } }
                putJsonObject("vault") { put("type", "string") }
            },
            required = required,
        )

        // Resolve the SvodTools for this call's target vault, enforcing the agent's grant. Returns
        // a denial/not-found CallToolResult instead when the vault isn't granted or doesn't exist.
        fun routed(req: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest): Pair<SvodTools?, io.modelcontextprotocol.kotlin.sdk.types.CallToolResult?> {
            val target = req.str("vault") ?: agent.primaryVault(defaultVaultId)
            // Empty grants ⇒ no restriction (single-vault / back-compat); otherwise enforce the grant.
            if (agent.vaults.isNotEmpty() && target !in agent.vaults)
                return null to ToolResult.denied("vault '$target' is not granted to agent '${agent.agentId}'").toCallToolResult()
            val t = toolsByVault(target) ?: return null to ToolResult.notFound("vault: $target").toCallToolResult()
            return t to null
        }

        server.addTool("read", "Read a note's current content + revision.", schema(mapOf("path" to "string"), listOf("path"))) { req ->
            val (t, d) = routed(req); d ?: t!!.read(agent, req.str("path")!!).toCallToolResult()
        }
        server.addTool("write", "Create or update a note (optimistic via expectedRevision).", schema(mapOf("path" to "string", "content" to "string", "expectedRevision" to "string"), listOf("path", "content"))) { req ->
            val (t, d) = routed(req); d ?: t!!.write(agent, req.str("path")!!, req.str("content") ?: "", req.str("expectedRevision")).toCallToolResult()
        }
        server.addTool("edit", "Partial edit: replace an exact substring in a note without resending the whole content. oldString must occur exactly once (add surrounding context to disambiguate) unless replaceAll=true.", schema(mapOf("path" to "string", "oldString" to "string", "newString" to "string", "replaceAll" to "boolean", "expectedRevision" to "string"), listOf("path", "oldString", "newString"))) { req ->
            val (t, d) = routed(req); d ?: t!!.edit(agent, req.str("path")!!, req.str("oldString") ?: "", req.str("newString") ?: "", req.bool("replaceAll", false), req.str("expectedRevision")).toCallToolResult()
        }
        server.addTool("delete", "Soft-delete a note to .trash/.", schema(mapOf("path" to "string", "expectedRevision" to "string"), listOf("path"))) { req ->
            val (t, d) = routed(req); d ?: t!!.delete(agent, req.str("path")!!, req.str("expectedRevision")).toCallToolResult()
        }
        server.addTool("move", "Move/rename a note.", schema(mapOf("from" to "string", "to" to "string", "expectedRevision" to "string"), listOf("from", "to"))) { req ->
            val (t, d) = routed(req); d ?: t!!.move(agent, req.str("from")!!, req.str("to")!!, req.str("expectedRevision")).toCallToolResult()
        }
        server.addTool("promote", "Promote a draft from messy/ into the curated vault.", schema(mapOf("from" to "string", "to" to "string", "expectedRevision" to "string"), listOf("from", "to"))) { req ->
            val (t, d) = routed(req); d ?: t!!.promote(agent, req.str("from")!!, req.str("to")!!, req.str("expectedRevision")).toCallToolResult()
        }
        server.addTool("search", "Hybrid search (keyword/semantic/hybrid) with filters.", schema(mapOf("query" to "string", "mode" to "string", "limit" to "integer"), listOf("query"))) { req ->
            val (t, d) = routed(req); d ?: t!!.search(agent, req.toSearchQuery()).toCallToolResult()
        }
        server.addTool("context_pack", "Assemble a cited context block. Default: token-budgeted hybrid recall. enumerate=true: return EVERY note matching the filters (type/tags) in full, unranked — the 'rule book' (all active policies/preferences) every turn.", schema(mapOf("query" to "string", "mode" to "string", "tokenBudget" to "integer", "type" to "string", "status" to "string", "enumerate" to "boolean"), emptyList())) { req ->
            val (t, d) = routed(req)
            d ?: run {
                // Pull a generous candidate pool, then trim to the token budget.
                val base = req.toSearchQuery()
                val q = base.copy(limit = maxOf(base.limit, 50))
                t!!.contextPack(agent, q, req.int("tokenBudget", 2000), req.bool("enumerate", false)).toCallToolResult()
            }
        }
        server.addTool("remember", "Promote an observation into durable typed memory (policy/preference/fact/episode). Dedups by content; fact/policy enter 'provisional'. Use 'supersedes' to revoke+replace a prior memory.", schema(mapOf("content" to "string", "type" to "string", "subject" to "string", "confidence" to "number", "source" to "string", "status" to "string", "into" to "string", "supersedes" to "string"), listOf("content"))) { req ->
            val (t, d) = routed(req)
            d ?: t!!.remember(agent, req.str("content") ?: "", req.str("type"), req.str("subject"), req.double("confidence"), req.str("source"), req.str("status"), req.str("into"), req.str("supersedes")).toCallToolResult()
        }
        server.addTool("list", "List note paths (optionally filtered by prefix).", schema(mapOf("pathPrefix" to "string"), emptyList())) { req ->
            val (t, d) = routed(req); d ?: t!!.list(agent, req.str("pathPrefix")).toCallToolResult()
        }
        server.addTool("history", "Commit history for a note.", schema(mapOf("path" to "string", "max" to "integer"), listOf("path"))) { req ->
            val (t, d) = routed(req); d ?: t!!.history(agent, req.str("path")!!, req.int("max", 50)).toCallToolResult()
        }
        server.addTool("diff", "Unified diff of a note between two revisions.", schema(mapOf("path" to "string", "from" to "string", "to" to "string"), listOf("path", "from", "to"))) { req ->
            val (t, d) = routed(req); d ?: t!!.diff(agent, req.str("path")!!, req.str("from")!!, req.str("to")!!).toCallToolResult()
        }
        server.addTool("get_revision", "Read a note's content at a specific revision.", schema(mapOf("path" to "string", "revision" to "string"), listOf("path", "revision"))) { req ->
            val (t, d) = routed(req); d ?: t!!.getRevision(agent, req.str("path")!!, req.str("revision")!!).toCallToolResult()
        }
        server.addTool("link", "Outgoing wikilinks for a note (resolved + unresolved).", schema(mapOf("path" to "string"), listOf("path"))) { req ->
            val (t, d) = routed(req); d ?: t!!.link(agent, req.str("path")!!).toCallToolResult()
        }
        server.addTool("graph_query", "1-hop link neighborhood (outlinks + backlinks).", schema(mapOf("path" to "string"), listOf("path"))) { req ->
            val (t, d) = routed(req); d ?: t!!.graphQuery(agent, req.str("path")!!).toCallToolResult()
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

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.bool(key: String, default: Boolean): Boolean =
    runCatching { arguments?.get(key)?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull() ?: default

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.double(key: String): Double? =
    runCatching { arguments?.get(key)?.jsonPrimitive?.content?.toDouble() }.getOrNull()

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.toSearchQuery(): SearchQuery {
    val text = str("query") ?: ""
    val mode = when (str("mode")?.lowercase()) {
        "keyword" -> SearchMode.KEYWORD
        "semantic" -> SearchMode.SEMANTIC
        else -> SearchMode.HYBRID
    }
    val limit = int("limit", 10)
    val filtersObj = arguments?.get("filters") as? JsonObject
    // Memory typing/lifecycle filters may be given under `filters` or as top-level args (convenient
    // for context_pack enumerate, e.g. {type:"policy", enumerate:true}).
    fun fld(key: String): String? =
        filtersObj?.get(key)?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: str(key)
    val filters = SearchFilters(
        tags = (filtersObj?.get("tags") as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
        pathPrefix = fld("pathPrefix"),
        createdFrom = runCatching { filtersObj?.get("createdFrom")?.jsonPrimitive?.long }.getOrNull(),
        createdTo = runCatching { filtersObj?.get("createdTo")?.jsonPrimitive?.long }.getOrNull(),
        type = fld("type"),
        status = fld("status"),
        includeAll = bool("includeAll", false) || (filtersObj?.get("includeAll")?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true),
    )
    return SearchQuery(text, filters, mode, limit)
}

private val RESULT_JSON = kotlinx.serialization.json.Json { encodeDefaults = true }

private fun ToolResult.toCallToolResult(): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(RESULT_JSON.encodeToString(JsonObject.serializer(), data))),
        isError = isError,
    )
