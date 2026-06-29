package dev.svod.engine.api

import dev.svod.engine.api.CreateAgentRequest
import dev.svod.engine.api.UpdateAgentRequest

/**
 * Runtime management of MCP agents (LLM access) for the App API. The production implementation
 * lives in [dev.svod.engine.lifecycle.AgentController]. Null wiring ⇒ endpoints return 501.
 */
interface AgentAdmin {
    /** Bad agentId pattern or bad role ⇒ 400. */
    class InvalidRequest(message: String) : Exception(message)
    /** Duplicate agentId ⇒ 409. */
    class Conflict(message: String) : Exception(message)
    /** tokenRef is a raw value, not a Secrets ref (env:/file:/keychain:) ⇒ 422. */
    class NotARef(message: String) : Exception(message)
    /** No agent with this id ⇒ 404. */
    class UnknownAgent(message: String) : Exception(message)

    fun list(): AgentsView
    suspend fun create(req: CreateAgentRequest): AgentSpecView
    suspend fun update(id: String, req: UpdateAgentRequest): AgentSpecView
    suspend fun delete(id: String)
}

data class AgentsView(val agents: List<AgentSpecView>, val mcpPort: Int)

data class AgentSpecView(
    val agentId: String,
    val name: String?,
    val role: String,
    val vaults: List<String>,
    val tokenRef: String,
    val prompt: String?,
)
