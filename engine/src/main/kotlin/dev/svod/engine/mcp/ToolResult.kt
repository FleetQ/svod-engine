package dev.svod.engine.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Outcome of a tool call, transport-agnostic.
 *
 * [isError] distinguishes protocol/permission failures (auth, role, rate limit, bad args —
 * the agent did something it may not) from domain outcomes (`ok`, `conflict`, `not_found` —
 * normal results the agent acts on). The MCP layer maps [isError] to `CallToolResult.isError`.
 * Every result carries a `status` field in [data] so agents can branch without parsing prose.
 */
data class ToolResult(val status: String, val data: JsonObject, val isError: Boolean = false) {
    companion object {
        fun ok(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): ToolResult =
            ToolResult("ok", buildJsonObject { put("status", "ok"); build() })

        fun conflict(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ToolResult =
            ToolResult("conflict", buildJsonObject { put("status", "conflict"); build() })

        fun notFound(message: String): ToolResult =
            ToolResult("not_found", buildJsonObject { put("status", "not_found"); put("message", message) })

        fun denied(message: String): ToolResult =
            ToolResult("denied", buildJsonObject { put("status", "denied"); put("message", message) }, isError = true)

        fun rateLimited(message: String): ToolResult =
            ToolResult("rate_limited", buildJsonObject { put("status", "rate_limited"); put("message", message) }, isError = true)

        fun badRequest(message: String): ToolResult =
            ToolResult("bad_request", buildJsonObject { put("status", "bad_request"); put("message", message) }, isError = true)

        /** A server-side invariant failed (e.g. an edit's post-transform integrity check) — the
         *  write was refused rather than committing corrupt content. Distinct from bad_request:
         *  the agent's inputs were valid; the engine caught its own would-be mistake. */
        fun internalError(message: String): ToolResult =
            ToolResult("internal_error", buildJsonObject { put("status", "internal_error"); put("message", message) }, isError = true)

        fun blocked(path: String, findings: List<String>): ToolResult =
            ToolResult("blocked", buildJsonObject {
                put("status", "blocked"); put("path", path)
                put("message", "write refused: possible secret(s) detected")
                putJsonArray("findings") { findings.forEach { add(it) } }
            }, isError = true)
    }
}
