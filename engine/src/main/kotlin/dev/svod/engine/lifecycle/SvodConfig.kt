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
    /** Legacy single-vault path. Synthesized into one default vault when [vaults] is empty. */
    val vaultPath: String? = null,
    /** Multi-vault configuration. When non-empty, takes precedence over [vaultPath]. */
    val vaults: List<VaultSettings> = emptyList(),
    /** Id of the default vault (used when a request omits a vault). Defaults to the first vault. */
    val defaultVault: String? = null,
    val host: String = "127.0.0.1",
    val appApiPort: Int = 7517,
    val mcpPort: Int = 7518,
    val embedder: EmbedderSettings = EmbedderSettings(),
    val agents: List<AgentSettings> = emptyList(),
    /** Legacy single-vault sync remotes (folded into the synthesized default vault). */
    val syncRemotes: List<String> = emptyList(),
    /** Stable identifier for this host (used for its sync proposal branch). */
    val hostId: String = "local",
    /** This host is the canonical merge authority (only it creates merge commits). */
    val mergeAuthority: Boolean = false,
    /** Secret scanning before commit (blocks leaked credentials from entering git). */
    val secretScanning: Boolean = false,
    /** Optional TLS for the MCP endpoint. */
    val mcpTls: TlsSettings? = null,
    /** Auto-sync interval in seconds; 0 disables the background loop (manual sync only). */
    val syncIntervalSeconds: Int = 0,
    /** Optional path to the reference web viewer (examples/web-viewer); served at `/` when set. */
    val webViewerPath: String? = null,
) {
    /** One vault: its own git repo, lock, index, and sync configuration. */
    @Serializable
    data class VaultSettings(
        val id: String,
        val path: String,
        val name: String? = null,
        val syncRemotes: List<String> = emptyList(),
        val hostId: String = "local",
        val mergeAuthority: Boolean = false,
        val syncIntervalSeconds: Int = 0,
    )

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
        /** Vault ids this agent may access. Empty ⇒ the default vault only. */
        val vaults: List<String> = emptyList(),
    )

    /** Optional TLS for the MCP endpoint (remote agents reach MCP over HTTPS). Passwords are secret refs. */
    @Serializable
    data class TlsSettings(
        val keystorePath: String,
        val keystorePassword: String,
        val keyAlias: String,
        val keyPassword: String,
    )

    /**
     * The resolved vault list: explicit [vaults], else a single vault synthesized from the legacy
     * [vaultPath] (folding in the legacy sync fields). Empty only if neither is configured.
     */
    fun resolvedVaults(): List<VaultSettings> =
        if (vaults.isNotEmpty()) vaults
        else listOfNotNull(vaultPath?.let {
            VaultSettings(defaultVault ?: "default", it, null, syncRemotes, hostId, mergeAuthority, syncIntervalSeconds)
        })

    fun defaultVaultId(): String = defaultVault ?: resolvedVaults().firstOrNull()?.id ?: "default"

    /** All configuration problems, empty when valid. */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val resolved = resolvedVaults()
        if (resolved.isEmpty()) errors += "no vault configured (set vaultPath or vaults)"
        if (resolved.any { it.id.isBlank() }) errors += "vault ids must be non-blank"
        if (resolved.any { it.path.isBlank() }) errors += "vault paths must be non-blank"
        val ids = resolved.map { it.id }
        if (ids.size != ids.toSet().size) errors += "vault ids must be unique"
        if (defaultVault != null && defaultVault !in ids) errors += "defaultVault '$defaultVault' is not among vault ids $ids"
        for (a in agents) for (v in a.vaults) if (v !in ids) errors += "agent '${a.agentId}' is granted unknown vault '$v'"
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
        if (syncRemotes.isNotEmpty() && hostId.isBlank()) errors += "hostId must be set when syncRemotes is configured"
        return errors
    }

    /** Path of the default vault (back-compat helper; multi-vault callers use [resolvedVaults]). */
    fun vault(): Path = Paths.get(resolvedVaults().first { it.id == defaultVaultId() }.path)

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
            token = dev.svod.engine.security.Secrets.resolve(a.token), // supports env:/file: refs
            agentId = a.agentId,
            role = role,
            name = a.name ?: a.agentId,
            email = a.email ?: "${a.agentId}@agents.svod.local",
            vaults = a.vaults.ifEmpty { listOf(defaultVaultId()) },
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
