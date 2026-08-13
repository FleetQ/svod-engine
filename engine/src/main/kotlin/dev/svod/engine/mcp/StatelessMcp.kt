package dev.svod.engine.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Wire constants for the two MCP revisions this server speaks.
 *
 * `2026-07-28` is stateless: SEP-2575 removed the initialize/initialized handshake (the client's
 * protocolVersion / clientInfo / clientCapabilities ride in `params._meta` on **every** request)
 * and SEP-2567 removed protocol-level sessions, so each request must stand alone. `2025-11-25`
 * keeps the handshake and `Mcp-Session-Id`; both are served side by side, detected per request.
 */
object McpProtocol {
    const val V2026_07_28 = "2026-07-28"
    const val V2025_11_25 = "2025-11-25"
    val SUPPORTED = listOf(V2026_07_28, V2025_11_25)

    /** SEP-2575 `params._meta` keys. */
    const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
    const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
    const val META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"

    /** SEP-2243 routing headers. */
    const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
    const val HEADER_METHOD = "Mcp-Method"
    const val HEADER_NAME = "Mcp-Name"
    const val HEADER_SESSION_ID = "Mcp-Session-Id"

    /**
     * SEP-2549 cache hints for `tools/list`. Svod's catalogue is a compile-time constant — the same
     * 15 tools with the same schemas for every agent — so a day is safe and a client that caches it
     * can skip the call entirely. `cacheScope: "server"` says the response does not vary by client
     * or session, so one cached copy serves every agent talking to this engine.
     */
    const val TOOLS_TTL_MS = 86_400_000L
    const val TOOLS_CACHE_SCOPE = "server"
}

/**
 * One tool, independent of the wire format that reaches it: the legacy path registers these on the
 * SDK's `Server`, the stateless path serves `tools/list` and `tools/call` straight from them. One
 * catalogue, so the two formats can never advertise or accept different tools.
 */
internal class ToolDef(
    val name: String,
    val description: String,
    val properties: JsonObject,
    val required: List<String>,
    val handler: suspend (CallToolRequest) -> CallToolResult,
) {
    fun schema(): ToolSchema = ToolSchema(properties = properties, required = required)

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("inputSchema") {
            put("type", "object")
            put("properties", properties)
            putJsonArray("required") { required.forEach { add(it) } }
        }
    }
}

/** What a 2026-07-28 client tells us about itself on every request, via `params._meta`. */
internal data class RequestMeta(
    val protocolVersion: String?,
    val clientInfo: JsonObject?,
    val clientCapabilities: JsonObject?,
) {
    companion object {
        val EMPTY = RequestMeta(null, null, null)

        fun of(body: JsonObject): RequestMeta {
            val meta = (body["params"] as? JsonObject)?.get("_meta") as? JsonObject ?: return EMPTY
            return RequestMeta(
                protocolVersion = meta[McpProtocol.META_PROTOCOL_VERSION]?.jsonPrimitive?.contentOrNull,
                clientInfo = meta[McpProtocol.META_CLIENT_INFO] as? JsonObject,
                clientCapabilities = meta[McpProtocol.META_CLIENT_CAPABILITIES] as? JsonObject,
            )
        }
    }
}

internal fun JsonObject.rpcMethod(): String? = this["method"]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.rpcId(): JsonElement? = this["id"]?.takeIf { it != JsonNull }

/**
 * SEP-2243: the routing headers exist so a proxy can dispatch without parsing the body, which only
 * holds if they agree with it. Returns the disagreement, or null when they match (or are absent —
 * the headers are optional, lying is what's forbidden).
 */
internal fun headerBodyMismatch(
    headerProtocolVersion: String?,
    headerMethod: String?,
    headerName: String?,
    body: JsonObject,
): String? {
    val method = body.rpcMethod()
    if (headerMethod != null && headerMethod != method)
        return "${McpProtocol.HEADER_METHOD}: '$headerMethod' disagrees with body method '${method ?: ""}'"

    val params = body["params"] as? JsonObject
    if (headerName != null) {
        val name = params?.get("name")?.jsonPrimitive?.contentOrNull
        if (headerName != name)
            return "${McpProtocol.HEADER_NAME}: '$headerName' disagrees with body params.name '${name ?: ""}'"
    }

    val metaVersion = RequestMeta.of(body).protocolVersion
    if (headerProtocolVersion != null && metaVersion != null && headerProtocolVersion != metaVersion)
        return "${McpProtocol.HEADER_PROTOCOL_VERSION}: '$headerProtocolVersion' disagrees with " +
            "params._meta '${McpProtocol.META_PROTOCOL_VERSION}' '$metaVersion'"

    return null
}

internal fun jsonRpcError(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    if (id != null) put("id", id) else put("id", JsonNull)
    putJsonObject("error") { put("code", code); put("message", message) }
}

internal fun jsonRpcResult(id: JsonElement?, result: JsonObject): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    put("result", result)
}

/**
 * Serve one stateless (2026-07-28) JSON-RPC message. Returns the response object, or null for a
 * notification (no `id`), which the caller answers with a bare 202.
 */
internal suspend fun dispatchStateless(
    body: JsonObject,
    tools: List<ToolDef>,
    serverName: String,
    serverVersion: String,
): JsonObject? {
    val method = body.rpcMethod()
    val id = body.rpcId()
    if (id == null) return null // notification — accepted, nothing to answer

    if (method == null) return jsonRpcError(id, -32600, "Invalid Request: no method")

    val meta = RequestMeta.of(body)

    return when (method) {
        // SEP-2575's replacement for `initialize`: capability discovery, no state left behind.
        "server/discover" -> jsonRpcResult(id, buildJsonObject {
            put("protocolVersion", meta.protocolVersion?.takeIf { it in McpProtocol.SUPPORTED } ?: McpProtocol.V2026_07_28)
            putJsonObject("serverInfo") { put("name", serverName); put("version", serverVersion) }
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
        })

        "tools/list" -> jsonRpcResult(id, buildJsonObject {
            putJsonArray("tools") { tools.forEach { add(it.toJson()) } }
            put("ttlMs", McpProtocol.TOOLS_TTL_MS)
            put("cacheScope", McpProtocol.TOOLS_CACHE_SCOPE)
        })

        "tools/call" -> {
            val params = body["params"] as? JsonObject
            val name = params?.get("name")?.jsonPrimitive?.contentOrNull
                ?: return jsonRpcError(id, -32602, "Invalid params: no tool name")
            val tool = tools.firstOrNull { it.name == name }
                ?: return jsonRpcError(id, -32602, "Unknown tool: $name")
            val args = params["arguments"] as? JsonObject
            val request = CallToolRequest(CallToolRequestParams(name = name, arguments = args))
            val result = tool.handler(request)
            jsonRpcResult(id, McpJson.encodeToJsonElement(CallToolResult.serializer(), result) as JsonObject)
        }

        "ping" -> jsonRpcResult(id, buildJsonObject { })

        else -> jsonRpcError(id, -32601, "Method not found: $method")
    }
}
