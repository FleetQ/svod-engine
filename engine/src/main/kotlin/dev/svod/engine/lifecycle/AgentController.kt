package dev.svod.engine.lifecycle

import dev.svod.engine.api.AgentAdmin
import dev.svod.engine.api.AgentSpecView
import dev.svod.engine.api.AgentsView
import dev.svod.engine.api.CreateAgentRequest
import dev.svod.engine.api.UpdateAgentRequest
import dev.svod.engine.mcp.AgentRegistry

/**
 * Runtime creation, update, and deletion of MCP agents. Config is persisted via [configStore]
 * and the in-memory [registry] is reloaded atomically after every mutation so the change is
 * visible to new MCP connections immediately, without an engine restart.
 */
class AgentController(
    private val configStore: ConfigStore,
    private val registry: AgentRegistry,
    private val host: String,
) : AgentAdmin {

    override fun list(): AgentsView {
        val cfg = configStore.config
        return AgentsView(cfg.agents.map { it.toView() }, cfg.mcpPort)
    }

    override suspend fun create(req: CreateAgentRequest): AgentSpecView {
        val id = req.agentId
        if (!ID_PATTERN.matches(id)) {
            throw AgentAdmin.InvalidRequest("invalid agentId '$id' (must match ${ID_PATTERN.pattern})")
        }
        validateRole(req.role)
        validateRef(req.tokenRef)
        if (configStore.config.agents.any { it.agentId == id }) {
            throw AgentAdmin.Conflict("agent id already exists: $id")
        }
        val new = SvodConfig.AgentSettings(
            token = req.tokenRef,
            agentId = id,
            role = req.role.uppercase(),
            name = req.name,
            vaults = req.vaults,
            prompt = req.prompt,
        )
        configStore.update { it.copy(agents = it.agents + new) }
        registry.reload(configStore.config.toAgentSpecs())
        return new.toView()
    }

    override suspend fun update(id: String, req: UpdateAgentRequest): AgentSpecView {
        val existing = configStore.config.agents.firstOrNull { it.agentId == id }
            ?: throw AgentAdmin.UnknownAgent("unknown agent: $id")
        req.role?.let { validateRole(it) }
        req.tokenRef?.let { validateRef(it) }
        val updated = existing.copy(
            name = req.name ?: existing.name,
            role = req.role?.uppercase() ?: existing.role,
            vaults = req.vaults ?: existing.vaults,
            token = req.tokenRef ?: existing.token,
            prompt = req.prompt ?: existing.prompt,
        )
        configStore.update { cfg ->
            cfg.copy(agents = cfg.agents.map { if (it.agentId == id) updated else it })
        }
        registry.reload(configStore.config.toAgentSpecs())
        return updated.toView()
    }

    override suspend fun delete(id: String) {
        if (configStore.config.agents.none { it.agentId == id }) {
            throw AgentAdmin.UnknownAgent("unknown agent: $id")
        }
        configStore.update { it.copy(agents = it.agents.filterNot { a -> a.agentId == id }) }
        registry.reload(configStore.config.toAgentSpecs())
    }

    private fun validateRole(role: String) {
        if (SvodConfig.ROLES.none { it.equals(role, ignoreCase = true) }) {
            throw AgentAdmin.InvalidRequest("role must be one of ${SvodConfig.ROLES}, was '$role'")
        }
    }

    private fun validateRef(ref: String) {
        if (!ref.startsWith("env:") && !ref.startsWith("file:") && !ref.startsWith("keychain:")) {
            throw AgentAdmin.NotARef("tokenRef must be a Secrets ref (env:/file:/keychain:), got '$ref'")
        }
    }

    private fun SvodConfig.AgentSettings.toView() = AgentSpecView(
        agentId = agentId,
        name = name,
        role = role,
        vaults = vaults,
        tokenRef = token,
        prompt = prompt,
    )

    companion object {
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]*$")
    }
}
