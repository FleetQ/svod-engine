package dev.svod.engine.mcp

import dev.svod.engine.core.Author

/** What an agent is allowed to do. */
enum class AgentRole {
    /** May call read-only tools only. */
    READ_ONLY,

    /** May call every tool, including mutations. */
    WRITE,
    ;

    val canWrite: Boolean get() = this == WRITE
}

/**
 * An authenticated agent. The [author] flows to git as the commit author, so every
 * mutation is attributable in history to the agent that made it.
 */
data class AgentIdentity(
    val agentId: String,
    val role: AgentRole,
    val author: Author,
    /** Vault ids this agent may access. Empty ⇒ the default vault only. */
    val vaults: List<String> = emptyList(),
) {
    /** The vault this agent's MCP session is bound to (its first grant, else [default]). */
    fun primaryVault(default: String): String = vaults.firstOrNull() ?: default
}

/**
 * Maps bearer tokens to agent identities. In-memory and immutable; the lifecycle/config
 * layer (Step 5) will load these from validated config / a secret store. Token comparison
 * is constant-time to avoid leaking validity via timing.
 */
class AgentRegistry(agents: List<AgentSpec>) {

    data class AgentSpec(
        val token: String,
        val agentId: String,
        val role: AgentRole,
        val name: String = agentId,
        val email: String = "$agentId@agents.svod.local",
        val vaults: List<String> = emptyList(),
    )

    @Volatile private var byToken: Map<String, AgentIdentity> = buildByToken(agents)
    @Volatile private var agentIds: Map<String, AgentIdentity> = buildByAgentId(agents)

    /**
     * Swap in a new agent set without restarting. Build the new maps fully first, then assign them
     * atomically so readers never see a partially-updated state.
     */
    fun reload(agents: List<AgentSpec>) {
        val newByToken = buildByToken(agents)
        val newByAgentId = buildByAgentId(agents)
        byToken = newByToken
        agentIds = newByAgentId
    }

    /** Resolve a bearer token to an agent, or null if unknown. Constant-time over tokens. */
    fun authenticate(token: String?): AgentIdentity? {
        if (token.isNullOrEmpty()) return null
        val map = byToken // read volatile field once
        var match: AgentIdentity? = null
        for ((known, identity) in map) {
            if (constantTimeEquals(known, token)) match = identity
        }
        return match
    }

    fun byAgentId(agentId: String): AgentIdentity? = agentIds[agentId]

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray(); val bb = b.toByteArray()
        var diff = ab.size xor bb.size
        for (i in bb.indices) diff = diff or (ab.getOrElse(i) { 0 }.toInt() xor bb[i].toInt())
        return diff == 0
    }

    private companion object {
        fun buildByToken(agents: List<AgentSpec>): Map<String, AgentIdentity> =
            agents.associate { it.token to AgentIdentity(it.agentId, it.role, Author(it.name, it.email), it.vaults) }

        fun buildByAgentId(agents: List<AgentSpec>): Map<String, AgentIdentity> =
            agents.associate { it.agentId to AgentIdentity(it.agentId, it.role, Author(it.name, it.email), it.vaults) }
    }
}
