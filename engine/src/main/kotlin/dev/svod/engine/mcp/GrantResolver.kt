package dev.svod.engine.mcp

/**
 * Per-grant role enforcement for MCP tools (ADR-0020 Decision 2). The single tool -> required-role
 * table lives here; the only caller is the ADR-0014 resolver `routed()` in [SvodMcpServer]. Inputs
 * are the agent, the target vault id and the tool name, never a note path (NFR-S1).
 */
object GrantResolver {
    val REQUIRED: Map<String, GrantRole> = buildMap {
        listOf(
            "read", "search", "context_pack", "list", "tree", "grep", "history", "diff", "get_revision", "link",
            "graph_query", "graph_communities", "graph_community", "graph_status",
        ).forEach { put(it, GrantRole.READER) }
        listOf("write", "edit", "move", "delete", "remember").forEach { put(it, GrantRole.WRITER) }
        put("promote", GrantRole.MAINTAINER)
    }

    /** An agent opts in to role enforcement when it is bound to a user or has an explicit `{vault, role}` grant. */
    fun optedIn(agent: AgentIdentity): Boolean = agent.userId != null || agent.grantRoles.isNotEmpty()

    /** null = allowed; otherwise the denial message. Call after the vault grant itself has passed. */
    fun deny(agent: AgentIdentity, vault: String, tool: String): String? {
        if (!optedIn(agent)) return null
        val grant = agent.grantRoles[vault] ?: GrantRole.WRITER
        val ceiling = if (agent.role == AgentRole.READ_ONLY) GrantRole.READER else GrantRole.MAINTAINER
        val effective = if (grant.ordinal <= ceiling.ordinal) grant else ceiling
        val required = REQUIRED[tool] ?: GrantRole.MAINTAINER
        return if (effective.covers(required)) null
        else "agent '${agent.agentId}' (role ${effective.label} in vault '$vault') may not call '$tool'"
    }
}
