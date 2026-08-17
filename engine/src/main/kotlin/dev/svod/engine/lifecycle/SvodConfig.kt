package dev.svod.engine.lifecycle

import dev.svod.engine.index.EmbedderConfig
import dev.svod.engine.index.EmbedderProvider
import dev.svod.engine.index.OnnxConfig
import dev.svod.engine.index.RerankerConfig
import dev.svod.engine.index.RerankerProvider
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
    /** Optional second-stage reranker over search results (off by default). */
    val reranker: RerankerSettings = RerankerSettings(),
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
    /** Optional off-site backup of every vault's canonical branch (disaster recovery). */
    val backup: BackupSettings? = null,
    /** Optional path to the reference web viewer (examples/web-viewer); served at `/` when set. */
    val webViewerPath: String? = null,
    /** Automatic re-sync of registered external sources (off by default; manual sync always works). */
    val sourceSync: SourceSyncSettings = SourceSyncSettings(),
    /** Indexing behavior (startup blocking gate). */
    val indexing: IndexingSettings = IndexingSettings(),
    /** When false (default), `messy/` drafts are quarantined out of default recall (search + context_pack). */
    val includeMessyInRecall: Boolean = false,
    /** Derived thematic graph over the vault (communities + summaries). Off by default. */
    val graph: GraphSettings = GraphSettings(),
) {
    /**
     * Configuration for the derived graph sidecar (`.svod/graph/`).
     *
     * **[enabled] defaults to false and [summaryProvider] to "none"**, mirroring [RerankerSettings]:
     * a fresh install behaves exactly as before, and neither a graph build nor any model call can
     * happen until the operator opts in.
     */
    @Serializable
    data class GraphSettings(
        val enabled: Boolean = false,
        /** Similarity neighbours kept per note. 0 ⇒ wikilink edges only. */
        val simEdgesPerNote: Int = 6,
        /** Minimum cosine for a similarity edge; below this the pair is treated as unrelated. */
        val simThreshold: Double = 0.75,
        /** Communities smaller than this are never summarised (noise, and one LLM call each). */
        val minCommunitySize: Int = 3,
        /** How many of the coarsest hierarchy levels get summaries; bounds the LLM call count. */
        val summariseTopLevels: Int = 1,
        val summaryProvider: String = "none",
        val summaryModel: String = "qwen2.5:7b-instruct",
        /** Defaults to the embedder's Ollama endpoint when null. */
        val summaryEndpoint: String? = null,
        val summaryInputChars: Int = 12_000,
        val summaryTimeoutSeconds: Int = 120,
        /** Build once at startup when the sidecar is missing or stale. Off by default. */
        val rebuildOnStartup: Boolean = false,
        /**
         * Attach notes written after a build to the existing themes, on each index sync — no LLM, no
         * re-clustering. Off by default; without it the thematic map describes the vault as of the
         * last full build only.
         */
        val incremental: Boolean = false,
        /**
         * Minimum cosine for attaching a new note to an existing theme. Null ⇒ reuse [simThreshold].
         *
         * Worth setting separately: [simThreshold] tuned high (0.88) deliberately leaves ~17% of
         * notes off the map, and attachment inherits that — measured, a real new note had no
         * neighbour above 0.88 and never appeared. Attachment is classification against a partition
         * that already exists, not structure-forming, so it can use a lower bar without changing any
         * community.
         */
        val attachThreshold: Double? = null,
    )
    /**
     * [blockStartup]=false (default) binds the App API immediately and builds the semantic index in
     * the background (keyword search works at once); =true keeps the legacy behavior of waiting for
     * the full index before serving — useful only for batch/one-shot runs.
     */
    @Serializable
    data class IndexingSettings(
        val blockStartup: Boolean = false,
    )
    /**
     * Drives the background [dev.svod.engine.sources.SourceScheduler]. [onStartup] runs one full
     * source sync (all vaults) at boot; [intervalMinutes] (>0) then re-syncs on that cadence. Both
     * off ⇒ no scheduler (sources sync only when an endpoint is called).
     */
    @Serializable
    data class SourceSyncSettings(
        val onStartup: Boolean = false,
        val intervalMinutes: Int? = null,
    )
    /**
     * Off-site backup destination. [remote] is a git remote URL (or a `Secrets` ref to one);
     * credentials must NOT be inline in the URL — supply them via the remote's credential helper
     * or a `Secrets` reference, so they never sit in plaintext config. Each vault's canonical
     * branch is pushed to `refs/svod/backup/<vaultId>` on this remote.
     *
     * Auto-backup (opt-in, drives [dev.svod.engine.sync.BackupScheduler], only runs when [enabled]):
     * [backupOnStartup] backs up once shortly after the engine is ready; [backupIntervalMinutes]
     * (>0) then backs up on that cadence; [backupOnChange] backs up after vault writes settle
     * (a short quiet period). All off ⇒ backup runs only on POST /api/v1/backup/now.
     *
     * **Two-way sync (opt-in, [syncEnabled]).** When set (and [enabled] with a [remote]), the vault
     * is a *synced* vault: the same [remote] is the bidirectional sync bus, the canonical ref is
     * `refs/svod/sync/<vaultId>`, and the one-way backup push (`refs/svod/backup/<vaultId>`) is
     * retired — the canonical sync ref IS the off-site copy. [syncIntervalMinutes] (default 3 when
     * null) is the background poll cadence; sync also runs on POST /api/v1/sync/now and debounced
     * after local writes settle.
     */
    @Serializable
    data class BackupSettings(
        val remote: String,
        val enabled: Boolean = true,
        val backupOnStartup: Boolean = false,
        val backupIntervalMinutes: Int? = null,
        val backupOnChange: Boolean = false,
        val syncEnabled: Boolean = false,
        val syncIntervalMinutes: Int? = null,
    ) {
        /** This vault replicates two-way (sync subsumes one-way backup) — needs an enabled remote. */
        fun isSynced(): Boolean = syncEnabled && enabled && remote.isNotBlank()
    }
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
        /** This vault's own off-site backup remote (each environment can back up to its own server). */
        val backup: BackupSettings? = null,
    )

    @Serializable
    data class EmbedderSettings(
        /** `local-onnx` (a.k.a. `onnx-local`), `local-ollama` (a.k.a. `ollama`), `remote-openai`, `none`. */
        val provider: String = "onnx-local",
        val onnxModelId: String = "multilingual-e5-small",
        val onnxLocalPath: String? = null,
        val ollamaModel: String = "zylonai/multilingual-e5-large",
        val ollamaEndpoint: String = "http://127.0.0.1:11434",
        /** Generic endpoint for `local-ollama` / `remote-openai` (overrides the provider-specific default). */
        val endpoint: String? = null,
        /** Generic model for `local-ollama` / `remote-openai` (overrides the provider-specific default). */
        val model: String? = null,
        /** OpenAI-compatible API key as a `Secrets` ref (env:/file:/keychain:) — never a raw key. */
        val apiKeyRef: String? = null,
        /** Cap of concurrent low-priority background embedding workers. */
        val maxThreads: Int = 2,
        /** Max texts per embedder call / per remote POST. null ⇒ auto (32 local, 96 remote). */
        val batchSize: Int? = null,
        /** Per-request timeout for HTTP providers, seconds (generous for serverless cold starts). */
        val requestTimeoutSeconds: Int = 60,
    )

    /**
     * Second-stage reranking (cross-encoder over the fused candidates). [provider] `remote` posts to
     * a TEI-compatible `/rerank` endpoint; `none` (default) disables it. [apiKeyRef] is a `Secrets`
     * ref (env:/file:/keychain:), never a raw key. [topK] bounds how many top candidates are
     * re-scored per query.
     */
    @Serializable
    data class RerankerSettings(
        val provider: String = "none",
        val endpoint: String? = null,
        val model: String? = null,
        val apiKeyRef: String? = null,
        val topK: Int = 50,
        val requestTimeoutSeconds: Int = 30,
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
        /** Optional system prompt surfaced to the UI (config-only metadata; not threaded into AgentSpec). */
        val prompt: String? = null,
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

    /** The configured sync remotes for [vaultId] (empty when sync is not configured for it). */
    fun syncRemotesFor(vaultId: String): List<String> =
        resolvedVaults().firstOrNull { it.id == vaultId }?.syncRemotes ?: emptyList()

    /** The auto-sync interval (seconds) configured for [vaultId]; 0 ⇒ manual only. */
    fun syncIntervalSecondsFor(vaultId: String): Int =
        resolvedVaults().firstOrNull { it.id == vaultId }?.syncIntervalSeconds ?: 0

    /** This host's id for [vaultId]'s sync proposals. */
    fun hostIdFor(vaultId: String): String =
        resolvedVaults().firstOrNull { it.id == vaultId }?.hostId ?: hostId

    /** Backup destination for [vaultId]: its own remote, else the global [backup] (back-compat). */
    fun backupFor(vaultId: String): BackupSettings? =
        resolvedVaults().firstOrNull { it.id == vaultId }?.backup ?: backup

    /** Sync role for [vaultId]: `authority` (merge authority), `follower` (has peers), or `solo`. */
    fun roleFor(vaultId: String): String {
        if (syncRemotesFor(vaultId).isEmpty()) return "solo"
        val authority = resolvedVaults().firstOrNull { it.id == vaultId }?.mergeAuthority ?: mergeAuthority
        return if (authority) "authority" else "follower"
    }

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
        if (embedder.maxThreads < 1) errors += "embedder.maxThreads must be >= 1, was ${embedder.maxThreads}"
        embedder.batchSize?.let { if (it < 1) errors += "embedder.batchSize must be >= 1, was $it" }
        if (embedder.requestTimeoutSeconds < 1) errors += "embedder.requestTimeoutSeconds must be >= 1, was ${embedder.requestTimeoutSeconds}"
        if (RERANKER_PROVIDERS.none { it.equals(reranker.provider, ignoreCase = true) }) {
            errors += "reranker.provider must be one of $RERANKER_PROVIDERS, was '${reranker.provider}'"
        }
        if (reranker.topK < 1) errors += "reranker.topK must be >= 1, was ${reranker.topK}"
        if (reranker.requestTimeoutSeconds < 1) errors += "reranker.requestTimeoutSeconds must be >= 1, was ${reranker.requestTimeoutSeconds}"
        val tokens = agents.map { it.token }
        if (tokens.any { it.isBlank() }) errors += "agent tokens must be non-blank"
        if (tokens.size != tokens.toSet().size) errors += "agent tokens must be unique"
        if (agents.map { it.agentId }.let { it.size != it.toSet().size }) errors += "agent ids must be unique"
        for (a in agents) {
            if (ROLES.none { it.equals(a.role, ignoreCase = true) }) errors += "agent '${a.agentId}' role must be one of $ROLES, was '${a.role}'"
        }
        if (syncRemotes.isNotEmpty() && hostId.isBlank()) errors += "hostId must be set when syncRemotes is configured"
        backup?.let { b ->
            if (b.remote.isBlank()) errors += "backup.remote must be non-blank"
            else if (remoteHasInlineCredentials(b.remote)) {
                errors += "backup.remote must not embed credentials inline; use a credential helper or a Secrets ref"
            }
            b.backupIntervalMinutes?.let { if (it < 0) errors += "backup.backupIntervalMinutes must be >= 0, was $it" }
            b.syncIntervalMinutes?.let { if (it < 0) errors += "backup.syncIntervalMinutes must be >= 0, was $it" }
        }
        return errors
    }

    /** Path of the default vault (back-compat helper; multi-vault callers use [resolvedVaults]). */
    fun vault(): Path = Paths.get(resolvedVaults().first { it.id == defaultVaultId() }.path)

    fun toEmbedderConfig(): EmbedderConfig {
        val provider = when (embedder.provider.lowercase()) {
            "none" -> EmbedderProvider.NONE
            "ollama", "local-ollama" -> EmbedderProvider.OLLAMA
            "openai", "remote-openai" -> EmbedderProvider.OPENAI
            else -> EmbedderProvider.ONNX_LOCAL // "onnx-local" / "local-onnx"
        }
        // Generic endpoint/model (new schema) override the provider-specific legacy fields.
        // Remote providers default to a larger batch (fewer POSTs ⇒ fewer serverless cold starts).
        val isRemote = provider == EmbedderProvider.OPENAI
        return EmbedderConfig(
            provider = provider,
            onnx = OnnxConfig(embedder.onnxModelId, embedder.onnxLocalPath?.let { Paths.get(it) }),
            ollamaModel = embedder.model ?: embedder.ollamaModel,
            ollamaEndpoint = embedder.endpoint ?: embedder.ollamaEndpoint,
            openaiModel = embedder.model ?: dev.svod.engine.index.OpenAiEmbedder.DEFAULT_MODEL,
            openaiEndpoint = embedder.endpoint ?: dev.svod.engine.index.OpenAiEmbedder.DEFAULT_ENDPOINT,
            openaiApiKeyRef = embedder.apiKeyRef,
            maxThreads = embedder.maxThreads,
            batchSize = embedder.batchSize ?: if (isRemote) 96 else 32,
            requestTimeoutSeconds = embedder.requestTimeoutSeconds,
        )
    }

    fun toRerankerConfig(): RerankerConfig {
        val provider = when (reranker.provider.lowercase()) {
            "remote", "remote-tei", "tei" -> RerankerProvider.REMOTE
            else -> RerankerProvider.NONE
        }
        return RerankerConfig(
            provider = provider,
            endpoint = reranker.endpoint ?: dev.svod.engine.index.RemoteReranker.DEFAULT_ENDPOINT,
            model = reranker.model ?: dev.svod.engine.index.RemoteReranker.DEFAULT_MODEL,
            // API key is a Secrets reference only — resolved here, never accepted/stored raw.
            apiKey = reranker.apiKeyRef?.takeIf { it.isNotBlank() }?.let { dev.svod.engine.security.Secrets.resolve(it) },
            topK = reranker.topK,
            requestTimeoutSeconds = reranker.requestTimeoutSeconds,
        )
    }

    fun toGraphConfig(): dev.svod.engine.graphrag.GraphConfig {
        val provider = when (graph.summaryProvider.lowercase()) {
            "ollama", "local-ollama" -> dev.svod.engine.graphrag.SummaryProvider.OLLAMA
            else -> dev.svod.engine.graphrag.SummaryProvider.NONE
        }
        return dev.svod.engine.graphrag.GraphConfig(
            enabled = graph.enabled,
            simEdgesPerNote = graph.simEdgesPerNote,
            simThreshold = graph.simThreshold,
            minCommunitySize = graph.minCommunitySize,
            summariseTopLevels = graph.summariseTopLevels,
            summaryInputChars = graph.summaryInputChars,
            rebuildOnStartup = graph.rebuildOnStartup,
            incremental = graph.incremental,
            attachThreshold = graph.attachThreshold,
            summary = dev.svod.engine.graphrag.SummaryLlmConfig(
                provider = provider,
                model = graph.summaryModel,
                // Reuse the embedder's Ollama endpoint by default — one server, one place to configure.
                endpoint = graph.summaryEndpoint ?: embedder.endpoint ?: embedder.ollamaEndpoint,
                requestTimeoutSeconds = graph.summaryTimeoutSeconds,
            ),
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

        // `user:password@host` userinfo in a URL-style remote (https://, ssh://, git://). The scp
        // form `git@host:path` carries a username but no password and is the normal ssh form, so it
        // is allowed; an embedded password (`:` before the `@` of the authority) is not.
        private val URL_SCHEME_REMOTE = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://([^/@]*)@""")

        /**
         * True when [remote] is a literal URL that embeds a password in its userinfo. `Secrets`
         * refs (`env:`/`file:`/`keychain:`) are exempt — the credential lives outside the value.
         */
        fun remoteHasInlineCredentials(remote: String): Boolean {
            if (remote.startsWith("env:") || remote.startsWith("file:") || remote.startsWith("keychain:")) return false
            val userinfo = URL_SCHEME_REMOTE.find(remote)?.groupValues?.get(1) ?: return false
            return ':' in userinfo // password present (user:pass@)
        }

        // Strips a `user:password@` (or `user@`) authority from a URL-style remote, leaving
        // `scheme://host/path`. A `Secrets` ref or scp-form `git@host:path` is returned unchanged.
        private val URL_USERINFO = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*@""")

        /** Redact any credentials embedded in a remote URL so it is safe to surface to a client. */
        fun redactRemote(remote: String): String = URL_USERINFO.replace(remote) { it.groupValues[1] }

        val PROVIDERS = listOf("onnx-local", "local-onnx", "ollama", "local-ollama", "remote-openai", "openai", "none")
        val RERANKER_PROVIDERS = listOf("none", "remote", "remote-tei", "tei")
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
