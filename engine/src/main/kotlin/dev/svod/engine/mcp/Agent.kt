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
)

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
    )

    private val byToken: Map<String, AgentIdentity> = agents.associate { spec ->
        spec.token to AgentIdentity(spec.agentId, spec.role, Author(spec.name, spec.email))
    }
    private val agentIds: Map<String, AgentIdentity> = agents.associate { spec ->
        spec.agentId to AgentIdentity(spec.agentId, spec.role, Author(spec.name, spec.email))
    }

    /** Resolve a bearer token to an agent, or null if unknown. Constant-time over tokens. */
    fun authenticate(token: String?): AgentIdentity? {
        if (token.isNullOrEmpty()) return null
        var match: AgentIdentity? = null
        for ((known, identity) in byToken) {
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
}
