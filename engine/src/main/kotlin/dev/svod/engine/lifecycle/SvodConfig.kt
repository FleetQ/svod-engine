package dev.svod.engine.lifecycle

import dev.svod.engine.index.EmbedderConfig
import dev.svod.engine.index.EmbedderProvider
import dev.svod.engine.index.OnnxConfig
import dev.svod.engine.mcp.AgentRegistry
import dev.svod.engine.mcp.AgentRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Centralized engine configuration, validated at startup. One file is the single place that
 * decides vault location, ports, embedder provider, and the agent tokens the MCP endpoint
 * accepts. Loopback is enforced here so the App API can never be misconfigured off 127.0.0.1.
 */
@Serializable
data class SvodConfig(
    val vaultPath: String,
    val host: String = "127.0.0.1",
    val appApiPort: Int = 7517,
    val mcpPort: Int = 7518,
    val embedder: EmbedderSettings = EmbedderSettings(),
    val agents: List<AgentSettings> = emptyList(),
    val syncRemotes: List<String> = emptyList(),
    /** Optional path to the reference web viewer (examples/web-viewer); served at `/` when set. */
    val webViewerPath: String? = null,
) {
    @Serializable
    data class EmbedderSettings(
        val provider: String = "onnx-local",
        val onnxModelId: String = "multilingual-e5-small",
        val onnxLocalPath: String? = null,
        val ollamaModel: String = "zylonai/multilingual-e5-large",
        val ollamaEndpoint: String = "http://127.0.0.1:11434",
    )

    @Serializable
    data class AgentSettings(
        val token: String,
        val agentId: String,
        val role: String,
        val name: String? = null,
        val email: String? = null,
    )

    /** All configuration problems, empty when valid. */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (vaultPath.isBlank()) errors += "vaultPath is blank"
        if (host !in LOOPBACK) errors += "host must be loopback (one of $LOOPBACK), was '$host'"
        if (appApiPort !in 0..65535) errors += "appApiPort out of range: $appApiPort"
        if (mcpPort !in 0..65535) errors += "mcpPort out of range: $mcpPort"
        // port 0 = ephemeral (any free port); only fixed ports must differ.
        if (appApiPort != 0 && appApiPort == mcpPort) errors += "appApiPort and mcpPort must differ ($appApiPort)"
        if (PROVIDERS.none { it.equals(embedder.provider, ignoreCase = true) }) {
            errors += "embedder.provider must be one of $PROVIDERS, was '${embedder.provider}'"
        }
        val tokens = agents.map { it.token }
        if (tokens.any { it.isBlank() }) errors += "agent tokens must be non-blank"
        if (tokens.size != tokens.toSet().size) errors += "agent tokens must be unique"
        if (agents.map { it.agentId }.let { it.size != it.toSet().size }) errors += "agent ids must be unique"
        for (a in agents) {
            if (ROLES.none { it.equals(a.role, ignoreCase = true) }) errors += "agent '${a.agentId}' role must be one of $ROLES, was '${a.role}'"
        }
        return errors
    }

    fun vault(): Path = Paths.get(vaultPath)

    fun toEmbedderConfig(): EmbedderConfig {
        val provider = when (embedder.provider.lowercase()) {
            "none" -> EmbedderProvider.NONE
            "ollama" -> EmbedderProvider.OLLAMA
            else -> EmbedderProvider.ONNX_LOCAL
        }
        return EmbedderConfig(
            provider = provider,
            onnx = OnnxConfig(embedder.onnxModelId, embedder.onnxLocalPath?.let { Paths.get(it) }),
            ollamaModel = embedder.ollamaModel,
            ollamaEndpoint = embedder.ollamaEndpoint,
        )
    }

    fun toAgentSpecs(): List<AgentRegistry.AgentSpec> = agents.map { a ->
        val role = if (a.role.equals("WRITE", ignoreCase = true)) AgentRole.WRITE else AgentRole.READ_ONLY
        AgentRegistry.AgentSpec(
            token = a.token,
            agentId = a.agentId,
            role = role,
            name = a.name ?: a.agentId,
            email = a.email ?: "${a.agentId}@agents.svod.local",
        )
    }

    companion object {
        val LOOPBACK = setOf("127.0.0.1", "::1", "localhost")
        val PROVIDERS = listOf("onnx-local", "ollama", "none")
        val ROLES = listOf("READ_ONLY", "WRITE")

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun load(path: Path): SvodConfig {
            require(Files.isRegularFile(path)) { "config file not found: $path" }
            return json.decodeFromString(serializer(), Files.readString(path))
        }

        fun loadOrThrowValidated(path: Path): SvodConfig {
            val config = load(path)
            val errors = config.validate()
            require(errors.isEmpty()) { "invalid config $path:\n - " + errors.joinToString("\n - ") }
            return config
        }

        fun default(vaultPath: Path): SvodConfig = SvodConfig(vaultPath = vaultPath.toString())

        fun toJson(config: SvodConfig): String = json.encodeToString(serializer(), config)
    }
}
