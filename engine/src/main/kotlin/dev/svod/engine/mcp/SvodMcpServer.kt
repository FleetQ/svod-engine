package dev.svod.engine.mcp

import dev.svod.engine.index.SearchFilters
import dev.svod.engine.index.SearchMode
import dev.svod.engine.index.SearchQuery
import io.ktor.http.ContentType
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
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
        // The POST handler peeks at the body to tell the two wire formats apart; the legacy
        // transport then parses it again for itself.
        install(DoubleReceive)
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
                        // The body is read here to pick a wire format and then again by the legacy
                        // transport, so DoubleReceive (installed above) must keep it replayable.
                        val raw = call.receiveText()
                        val parsed = runCatching { McpJson.parseToJsonElement(raw) }.getOrNull()
                        if (isLegacyHandshake(call, parsed)) {
                            val transport = getOrCreateTransport(call, transports) ?: return@post
                            transport.handleRequest(null, call)
                        } else {
                            handleStateless(call, parsed)
                        }
                    }
                    delete {
                        val transport = findTransport(call, transports) ?: return@delete
                        transport.handleRequest(null, call)
                    }
                }
            }
        }
    }

    /**
     * Which of the two wire formats this POST is in. A 2025-11-25 client is identifiable by exactly
     * two things: it carries the session id it was handed, or it is asking for one. Everything else
     * — including a bare `tools/list` with no headers at all — is served statelessly, which is what
     * a 2026-07-28 client sends and what the legacy path would only reject for having no session.
     */
    private fun isLegacyHandshake(call: ApplicationCall, parsed: JsonElement?): Boolean {
        if (call.request.header(McpProtocol.HEADER_SESSION_ID) != null) return true
        val body = parsed as? JsonObject ?: return false
        return body.rpcMethod() == "initialize"
    }

    /** Serve a 2026-07-28 request: no handshake, no session — validate, dispatch, answer. */
    private suspend fun handleStateless(call: ApplicationCall, parsed: JsonElement?) {
        if (parsed == null) {
            respondJson(call, HttpStatusCode.BadRequest, jsonRpcError(null, -32700, "Parse error"))
            return
        }
        val agentId = call.principal<UserIdPrincipal>()?.name
        val agent = agentId?.let { registry.byAgentId(it) }
        if (agent == null) {
            call.respond(HttpStatusCode.Unauthorized, "Unknown agent")
            return
        }

        val messages = when (parsed) {
            is JsonObject -> listOf(parsed)
            is JsonArray -> parsed.map { it as? JsonObject ?: return respondJson(call, HttpStatusCode.BadRequest, jsonRpcError(null, -32600, "Invalid Request: batch entry is not an object")) }
            else -> return respondJson(call, HttpStatusCode.BadRequest, jsonRpcError(null, -32600, "Invalid Request"))
        }

        val headerVersion = call.request.header(McpProtocol.HEADER_PROTOCOL_VERSION)
        val headerMethod = call.request.header(McpProtocol.HEADER_METHOD)
        val headerName = call.request.header(McpProtocol.HEADER_NAME)
        for (message in messages) {
            val mismatch = headerBodyMismatch(headerVersion, headerMethod, headerName, message)
            if (mismatch != null) {
                log.info("rejecting MCP request from agent '{}': {}", agent.agentId, mismatch)
                respondJson(call, HttpStatusCode.BadRequest, jsonRpcError(message.rpcId(), -32600, "Invalid Request: $mismatch"))
                return
            }
        }

        val tools = buildTools(agent)
        val responses = messages.mapNotNull { dispatchStateless(it, tools, SERVER_NAME, SERVER_VERSION) }
        when {
            responses.isEmpty() -> call.respond(HttpStatusCode.Accepted)
            parsed is JsonArray -> respondJson(call, HttpStatusCode.OK, JsonArray(responses))
            else -> respondJson(call, HttpStatusCode.OK, responses.first())
        }
    }

    private suspend fun respondJson(call: ApplicationCall, status: HttpStatusCode, body: JsonElement) {
        call.respondText(McpJson.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json, status)
    }

    private suspend fun findTransport(call: ApplicationCall, transports: ConcurrentMap<String, StreamableHttpServerTransport>): StreamableHttpServerTransport? {
        val sessionId = call.request.header(SESSION_HEADER)
        if (sessionId.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "No valid session ID")
            return null
        }
        val transport = transports[sessionId]
        if (transport == null) {
            log.info("unknown MCP session {} ({} {}) → 404; live sessions: {}", sessionId, call.request.local.method.value, call.request.local.uri, transports.size)
            call.respond(HttpStatusCode.NotFound, "Session not found")
        }
        return transport
    }

    private suspend fun getOrCreateTransport(call: ApplicationCall, transports: ConcurrentMap<String, StreamableHttpServerTransport>): StreamableHttpServerTransport? {
        val sessionId = call.request.header(SESSION_HEADER)
        if (sessionId != null) {
            val transport = transports[sessionId]
            if (transport == null) {
                // A stale id after an engine restart is the expected cause (in-memory sessions);
                // the client must re-initialize. Invisible in logs during the 2026-07-03 incident.
                log.info("unknown MCP session {} (POST) → 404; live sessions: {}", sessionId, transports.size)
                call.respond(HttpStatusCode.NotFound, "Session not found")
            }
            return transport
        }

        val agentId = call.principal<UserIdPrincipal>()?.name
        val agent = agentId?.let { registry.byAgentId(it) }
        if (agent == null) {
            call.respond(HttpStatusCode.Unauthorized, "Unknown agent")
            return null
        }

        val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true))
        transport.setOnSessionInitialized { id ->
            transports[id] = transport
            log.info("MCP session {} initialized for agent '{}'; live sessions: {}", id, agent.agentId, transports.size)
        }
        transport.setOnSessionClosed { id ->
            transports.remove(id)
            log.info("MCP session {} closed; live sessions: {}", id, transports.size)
        }

        val server = buildServer(agent)
        server.onClose { transport.sessionId?.let { transports.remove(it) } }
        server.createSession(transport)
        return transport
    }

    /** A fresh MCP server for [agent], serving the same catalogue as the stateless path. */
    private fun buildServer(agent: AgentIdentity): Server {
        val server = Server(
            Implementation(name = SERVER_NAME, version = SERVER_VERSION),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        buildTools(agent).forEach { tool ->
            server.addTool(tool.name, tool.description, tool.schema()) { req -> tool.handler(req) }
        }
        return server
    }

    /** The tool catalogue bound to [agent]; each call selects its target vault (default = the agent's). */
    private fun buildTools(agent: AgentIdentity): List<ToolDef> {
        val tools = mutableListOf<ToolDef>()

        // Every per-vault tool's schema gets an optional `vault` (default = the agent's vault). A
        // call may target any vault the agent is granted; an ungranted/unknown vault is rejected.
        fun props(props: Map<String, String>) = buildJsonObject {
            props.forEach { (name, type) -> putJsonObject(name) { put("type", type) } }
            putJsonObject("vault") { put("type", "string") }
        }

        fun tool(
            name: String,
            description: String,
            properties: Map<String, String>,
            required: List<String>,
            handler: suspend (io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest) -> CallToolResult,
        ) {
            tools += ToolDef(name, description, props(properties), required, handler)
        }

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

        tool("read", "Read a note's current content + revision.", mapOf("path" to "string"), listOf("path")) { req ->
            val (t, d) = routed(req); d ?: t!!.read(agent, req.str("path")!!).toCallToolResult()
        }
        tool("write", "Create or update a note (optimistic via expectedRevision).", mapOf("path" to "string", "content" to "string", "expectedRevision" to "string"), listOf("path", "content")) { req ->
            val (t, d) = routed(req); d ?: t!!.write(agent, req.str("path")!!, req.str("content") ?: "", req.str("expectedRevision")).toCallToolResult()
        }
        tool("edit", "Partial edit: replace an exact substring in a note without resending the whole content. oldString must occur exactly once (add surrounding context to disambiguate) unless replaceAll=true.", mapOf("path" to "string", "oldString" to "string", "newString" to "string", "replaceAll" to "boolean", "expectedRevision" to "string"), listOf("path", "oldString", "newString")) { req ->
            val (t, d) = routed(req); d ?: t!!.edit(agent, req.str("path")!!, req.str("oldString") ?: "", req.str("newString") ?: "", req.bool("replaceAll", false), req.str("expectedRevision")).toCallToolResult()
        }
        tool("delete", "Soft-delete a note to .trash/.", mapOf("path" to "string", "expectedRevision" to "string"), listOf("path")) { req ->
            val (t, d) = routed(req); d ?: t!!.delete(agent, req.str("path")!!, req.str("expectedRevision")).toCallToolResult()
        }
        tool("move", "Move/rename a note.", mapOf("from" to "string", "to" to "string", "expectedRevision" to "string"), listOf("from", "to")) { req ->
            val (t, d) = routed(req); d ?: t!!.move(agent, req.str("from")!!, req.str("to")!!, req.str("expectedRevision")).toCallToolResult()
        }
        tool("promote", "Promote a draft from messy/ into the curated vault.", mapOf("from" to "string", "to" to "string", "expectedRevision" to "string"), listOf("from", "to")) { req ->
            val (t, d) = routed(req); d ?: t!!.promote(agent, req.str("from")!!, req.str("to")!!, req.str("expectedRevision")).toCallToolResult()
        }
        tool("search", "Hybrid search (keyword/semantic/hybrid) with filters.", mapOf("query" to "string", "mode" to "string", "limit" to "integer"), listOf("query")) { req ->
            val (t, d) = routed(req); d ?: t!!.search(agent, req.toSearchQuery()).toCallToolResult()
        }
        tool("context_pack", "Assemble a cited context block. Default: token-budgeted hybrid recall. enumerate=true: return EVERY note matching the filters (type/tags) in full, unranked — the 'rule book' (all active policies/preferences) every turn. graphExpand=true: also pull the 1-hop wikilink neighbourhood of the top hits into any leftover budget (those blocks are marked viaGraph).", mapOf("query" to "string", "mode" to "string", "tokenBudget" to "integer", "type" to "string", "status" to "string", "enumerate" to "boolean", "graphExpand" to "boolean"), emptyList()) { req ->
            val (t, d) = routed(req)
            d ?: run {
                // Pull a generous candidate pool, then trim to the token budget.
                val base = req.toSearchQuery()
                val q = base.copy(limit = maxOf(base.limit, 50))
                t!!.contextPack(agent, q, req.int("tokenBudget", 2000), req.bool("enumerate", false), req.bool("graphExpand", false)).toCallToolResult()
            }
        }
        tool("remember", "Promote an observation into durable typed memory (policy/preference/fact/episode). Classifies the incoming memory against existing memory of the same type+subject and returns 'classification' (NEW|DUPLICATE|UPDATE|CONTRADICTION|UNCERTAIN) with 'relatedNote' and 'confidence': DUPLICATE is a no-op, UPDATE revokes+links its predecessor, CONTRADICTION keeps BOTH sides linked by 'contradicts' (never overwrites), UNCERTAIN is stored with 'needs-review: true'. fact/policy enter 'provisional'. Use 'supersedes' to declare a replacement explicitly.", mapOf("content" to "string", "type" to "string", "subject" to "string", "confidence" to "number", "source" to "string", "status" to "string", "into" to "string", "supersedes" to "string"), listOf("content")) { req ->
            val (t, d) = routed(req)
            d ?: t!!.remember(agent, req.str("content") ?: "", req.str("type"), req.str("subject"), req.double("confidence"), req.str("source"), req.str("status"), req.str("into"), req.str("supersedes")).toCallToolResult()
        }
        tool("list", "List note paths (optionally filtered by prefix).", mapOf("pathPrefix" to "string"), emptyList()) { req ->
            val (t, d) = routed(req); d ?: t!!.list(agent, req.str("pathPrefix")).toCallToolResult()
        }
        tool("history", "Commit history for a note.", mapOf("path" to "string", "max" to "integer"), listOf("path")) { req ->
            val (t, d) = routed(req); d ?: t!!.history(agent, req.str("path")!!, req.int("max", 50)).toCallToolResult()
        }
        tool("diff", "Unified diff of a note between two revisions.", mapOf("path" to "string", "from" to "string", "to" to "string"), listOf("path", "from", "to")) { req ->
            val (t, d) = routed(req); d ?: t!!.diff(agent, req.str("path")!!, req.str("from")!!, req.str("to")!!).toCallToolResult()
        }
        tool("get_revision", "Read a note's content at a specific revision.", mapOf("path" to "string", "revision" to "string"), listOf("path", "revision")) { req ->
            val (t, d) = routed(req); d ?: t!!.getRevision(agent, req.str("path")!!, req.str("revision")!!).toCallToolResult()
        }
        tool("link", "Outgoing wikilinks for a note (resolved + unresolved).", mapOf("path" to "string"), listOf("path")) { req ->
            val (t, d) = routed(req); d ?: t!!.link(agent, req.str("path")!!).toCallToolResult()
        }
        tool("graph_query", "1-hop link neighborhood (outlinks + backlinks).", mapOf("path" to "string"), listOf("path")) { req ->
            val (t, d) = routed(req); d ?: t!!.graphQuery(agent, req.str("path")!!).toCallToolResult()
        }
        tool("graph_communities", "Thematic communities of the vault with pre-computed summaries. With 'query', ranked by similarity to it; without, the coarsest level by size. Returns EVIDENCE (summaries + member paths) — synthesise the answer yourself. Consults no model.", mapOf("query" to "string", "level" to "integer", "limit" to "integer"), emptyList()) { req ->
            val (t, d) = routed(req)
            d ?: t!!.graphCommunities(agent, req.str("query"), req.int("level", -1).takeIf { it >= 0 }, req.int("limit", 20)).toCallToolResult()
        }
        tool("graph_status", "Build state of the derived thematic graph: whether it exists, when it was built, and whether it is stale relative to HEAD.", emptyMap(), emptyList()) { req ->
            val (t, d) = routed(req); d ?: t!!.graphStatus(agent).toCallToolResult()
        }
        return tools
    }

    companion object {
        private const val SESSION_HEADER = "mcp-session-id"
        private const val SERVER_NAME = "svod"
        private const val SERVER_VERSION = "0.1.0"
        private val log = org.slf4j.LoggerFactory.getLogger(SvodMcpServer::class.java)
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
