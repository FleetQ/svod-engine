package dev.svod.engine.api

import kotlinx.serialization.Serializable

/** Wire DTOs for the App API. Field names/shapes mirror `contract/openapi.yaml`. */

@Serializable
data class HealthDto(val status: String = "ok")

@Serializable
data class ReadyDto(val ready: Boolean, val engine: Boolean, val index: Boolean)

@Serializable
data class ErrorDto(val error: String, val message: String)

@Serializable
data class FileContentDto(val path: String, val revision: String, val content: String)

@Serializable
data class WriteRequestDto(val content: String, val expectedRevision: String? = null)

@Serializable
data class WriteResultDto(val path: String, val revision: String, val commit: String)

@Serializable
data class ConflictBodyDto(
    val path: String,
    val expected: String? = null,
    val current: String? = null,
    val currentContent: String? = null,
)

@Serializable
data class MoveRequestDto(val from: String, val to: String, val expectedRevision: String? = null)

@Serializable
data class MoveResultDto(val path: String, val revision: String, val commit: String, val rewrittenBacklinks: List<String>)

@Serializable
data class RestoreRequestDto(val trashPath: String, val to: String? = null)

@Serializable
data class TreeNodeDto(val name: String, val path: String, val type: String, val children: List<TreeNodeDto>? = null)

@Serializable
data class CommitInfoDto(val commit: String, val author: String, val email: String, val epochSeconds: Long, val message: String)

@Serializable
data class HistoryDto(val commits: List<CommitInfoDto>)

@Serializable
data class DiffResultDto(val path: String, val from: String, val to: String, val diff: String)

@Serializable
data class SearchHitDto(
    val path: String,
    val heading: String,
    val snippet: String,
    val score: Double,
    val matchedKeyword: Boolean,
    val matchedSemantic: Boolean,
    val tags: List<String>,
    /** Vault this hit belongs to (useful for federated `across=true` search). */
    val vault: String? = null,
    /** Cheap ~4-chars/token estimate of this hit's snippet (same estimator as context_pack). Additive. */
    val tokens: Int = 0,
)

@Serializable
data class SearchResultDto(val mode: String, val hits: List<SearchHitDto>)

@Serializable
data class GraphNodeDto(val id: String, val path: String)

@Serializable
data class GraphEdgeDto(val source: String, val target: String)

@Serializable
data class GraphDto(val nodes: List<GraphNodeDto>, val edges: List<GraphEdgeDto>, val unresolved: List<GraphEdgeDto>)

/**
 * One thematic community of the derived graph. [summary] is null when no summary provider was active
 * at build time — the community is still fully usable, labelled by [title].
 */
@Serializable
data class GraphCommunityDto(
    val id: String,
    val level: Int,
    val title: String,
    val summary: String? = null,
    val size: Int,
    val members: List<String>,
    /**
     * ADDED in 0.26.0. Members attached incrementally after this community was summarised, so a
     * reader can tell that [summary] describes slightly fewer notes than [size] counts.
     */
    val addedSinceSummary: Int = 0,
)

/** [stale] means the vault advanced past the build's HEAD; results are still served (design §8). */
@Serializable
data class GraphCommunitiesDto(
    val state: String,
    val stale: Boolean,
    val communities: List<GraphCommunityDto>,
)

@Serializable
data class OutlinkDto(val target: String, val resolved: String? = null)

@Serializable
data class FileLinksDto(
    val path: String,
    val outlinks: List<OutlinkDto>,
    val backlinks: List<String>,
    val unresolved: List<String>,
    /** Cross-vault backlinks as global ids ("vault:path") — notes in OTHER vaults that link here. */
    val crossVaultBacklinks: List<String> = emptyList(),
)

@Serializable
data class TagCountDto(val tag: String, val count: Int)

@Serializable
data class TagsDto(val tags: List<TagCountDto>)

@Serializable
data class SettingsDto(
    val vaultPath: String,
    val apiVersion: String,
    val embedderProvider: String,
    val embedderModel: String,
    val embedderDim: Int,
    val host: String,
    /** Structured embedder view (additive; preferred over the flat embedder* fields). */
    val embedder: EmbedderInfoDto,
    /** Active second-stage reranker (provider `none` when disabled). */
    val reranker: RerankerInfoDto,
)

/** The active reranker, for the read-only settings view. */
@Serializable
data class RerankerInfoDto(
    val provider: String,
    val model: String,
    val active: Boolean,
)

/** The active embedder for a vault. [endpoint] is null for in-process providers (onnx/none). */
@Serializable
data class EmbedderInfoDto(val provider: String, val model: String, val endpoint: String? = null, val dimension: Int)

/** Background-embedding progress (drives the index.progress WS event + GET /index/status). */
@Serializable
data class EmbeddingStatusDto(
    val state: String, // idle | running | paused | error
    val done: Int,
    val total: Int,
    val provider: String,
    val model: String,
    val error: String? = null,
    /** Rolling embed throughput (chunks/sec) while running; null when idle/not yet measurable. */
    val ratePerSec: Double? = null,
    /** Estimated seconds remaining for the current pass; null when idle/not yet measurable. */
    val etaSeconds: Long? = null,
)

@Serializable
data class IndexStatusDto(
    val docCount: Int,
    val headIndexed: String? = null,
    val model: String,
    val dim: Int,
    /** True once BM25/keyword search is consistent with HEAD (semantic may still be filling). */
    val keywordReady: Boolean = true,
    val embedding: EmbeddingStatusDto,
)

/** Request to switch the active embedder (PUT /embedder, POST /embedder/test). */
@Serializable
data class EmbedderRequestDto(
    val provider: String,
    val model: String? = null,
    val endpoint: String? = null,
    /** API key as a Secrets ref only (env:/file:/keychain:) — a raw key is rejected. */
    val apiKeyRef: String? = null,
    val maxThreads: Int? = null,
)

/** Result of probing an embedder spec (POST /embedder/test). */
@Serializable
data class EmbedderTestResultDto(
    val ok: Boolean,
    val dimension: Int? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
)

/** Models a provider can serve (POST /embedder/models). Empty list ⇒ the UI falls back to manual entry. */
@Serializable
data class EmbedderModelsDto(
    val provider: String,
    val models: List<EmbedderModelOptionDto>,
)

/** One selectable model; [dimension] is included only when cheaply known (omitted otherwise). */
@Serializable
data class EmbedderModelOptionDto(
    val id: String,
    val dimension: Int? = null,
)

@Serializable
data class ConflictEntryDto(
    val path: String,
    val reasons: List<String> = emptyList(),
    // base/ours/theirs back a 3-way merge UI; any may be null (e.g. added on one side only).
    val base: String? = null,
    val ours: String? = null,
    val theirs: String? = null,
    val ts: Long = 0,
)

@Serializable
data class ConflictsDto(val conflicts: List<ConflictEntryDto>)

@Serializable
data class ResolveConflictRequestDto(val path: String, val content: String, val expectedRevision: String? = null)

@Serializable
data class ImportRequestDto(val source: String, val into: String? = null, val vault: String? = null, val followSymlinks: Boolean = false)

@Serializable
data class ImportResultDto(val imported: List<String>, val unchanged: List<String>, val skipped: List<String>)

@Serializable
data class RegisterSourceRequestDto(
    val path: String,
    val into: String? = null,
    val followSymlinks: Boolean = false,
    val prune: Boolean = false,
    val autoSync: Boolean = false,
    val writeBack: Boolean = false,
)

/** Partial update of a registered source; null fields are left unchanged. */
@Serializable
data class PatchSourceRequestDto(
    val autoSync: Boolean? = null,
    val followSymlinks: Boolean? = null,
    val prune: Boolean? = null,
    val writeBack: Boolean? = null,
)

@Serializable
data class ExternalSourceDto(
    val id: String,
    val path: String,
    val into: String,
    val followSymlinks: Boolean,
    val prune: Boolean,
    val autoSync: Boolean = false,
    /** Two-way: vault edits to synced paths flow back to the external files (0.20.0). */
    val writeBack: Boolean = false,
    /** Live, read-only: a filesystem watcher for this source is currently running. */
    val watching: Boolean = false,
    val lastSyncedAt: String? = null,
    /** Vault paths whose local edits blocked the external update at the last sync. */
    val conflicts: List<String> = emptyList(),
)

/** Resolve one conflicted synced path: `strategy` = "takeExternal" | "keepVault". */
@Serializable
data class SourceResolveRequest(val path: String, val strategy: String)

@Serializable
data class SourceSyncResultDto(
    val id: String,
    val created: List<String> = emptyList(),
    val updated: List<String> = emptyList(),
    val unchanged: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val orphaned: List<String> = emptyList(),
    val deleted: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    /** Vault edits written back to the external files (writeBack sources, 0.20.0). */
    val pushed: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class VaultInfoDto(
    val id: String,
    val name: String,
    val default: Boolean,
    val sync: SyncStatusDto? = null,
    /** The caller's access to this vault: `admin` | `editor` | `reader` (0.30.0; absent on older engines). */
    val role: String? = null,
)

@Serializable
data class VaultsDto(val vaults: List<VaultInfoDto>)

/** Request to create a new vault at runtime (POST /api/v1/vaults). name/path are derived when null. */
@Serializable
data class CreateVaultRequest(val id: String, val name: String? = null, val path: String? = null)

/**
 * Result of DELETE /api/v1/vaults/{id}. [path] is the vault's on-disk directory that was removed
 * (filesDeleted=true) or that the caller should now dispose of, e.g. move to the OS Trash
 * (filesDeleted=false).
 */
@Serializable
data class DeleteVaultResultDto(val id: String, val path: String, val filesDeleted: Boolean)

@Serializable
data class WriteStatsDto(val count: Long, val avgMs: Double, val maxMs: Double, val lastMs: Double)

@Serializable
data class IndexLagDto(val docCount: Int, val head: String? = null, val indexedHead: String? = null, val lagging: Boolean)

@Serializable
data class SyncStatusDto(
    val role: String,
    val lastHead: String? = null,
    val conflicts: Int,
    /** Live sync state: inSync | syncing | conflicts | offline | error (null for non-synced vaults). */
    val syncStatus: String? = null,
    val lastSyncedAt: String? = null,
)

@Serializable
data class MetricsDto(
    val write: WriteStatsDto,
    val queueDepth: Int,
    val peakQueueDepth: Int,
    val index: IndexLagDto,
    val conflicts: Int,
    val sync: SyncStatusDto? = null,
)

// ---- Ops surface: per-vault backup / sync-config / maintenance (the UI's Sync & Backup panel) ----
// Wire shapes match FleetQ/svod-ui-macos exactly. Credentials in any remote URL are REDACTED before
// they leave the process (invariant: secrets never on the wire).

/**
 * Request to set a vault's backup destination and (optionally) its auto-backup schedule. [remote]
 * must be credential-free or a `Secrets` ref. The three schedule fields are additive and optional;
 * omitting one leaves that part of the schedule disabled (its default).
 */
@Serializable
data class BackupConfigRequestDto(
    val remote: String,
    val enabled: Boolean,
    val backupOnStartup: Boolean = false,
    val backupIntervalMinutes: Int? = null,
    val backupOnChange: Boolean = false,
    /** Two-way sync: when true the same [remote] is the bidirectional bus (one-way backup retired). */
    val syncEnabled: Boolean = false,
    /** Background sync poll cadence in minutes; null ⇒ engine default (3). */
    val syncIntervalMinutes: Int? = null,
)

/** A vault's sync + backup configuration (GET /sync/config; also the body PUT /settings/backup returns). */
@Serializable
data class SyncConfigDto(
    val backupRemote: String? = null,
    val backupEnabled: Boolean = false,
    /** Auto-backup schedule (all opt-in; only active when backupEnabled). */
    val backupOnStartup: Boolean = false,
    val backupIntervalMinutes: Int? = null,
    val backupOnChange: Boolean = false,
    /** Observable status: ISO-8601 instant + sha of the last successful backup (null until one runs). */
    val lastBackupAt: String? = null,
    val lastBackupHead: String? = null,
    val syncPeers: List<String> = emptyList(),
    val role: String? = null,        // "synced" | "authority" | "follower" | "solo"
    val hostId: String? = null,
    /** Two-way sync (subsumes one-way backup when on); same remote as [backupRemote]. */
    val syncEnabled: Boolean = false,
    val syncIntervalMinutes: Int? = null,
    /** Live sync state: inSync | syncing | conflicts | offline | error (null until first sync). */
    val syncStatus: String? = null,
    val lastSyncedAt: String? = null,
)

/** Ack for POST /api/v1/maintenance/reindex (index self-heal from git HEAD). */
@Serializable
data class MaintenanceAckDto(val started: Boolean, val docCount: Int? = null)

/**
 * Ack for POST /api/v1/backup/now. [head] = backup commit sha, null when there is nothing to back up.
 * [noChange] is true when the backup succeeded but had nothing new to push (already up to date) — still
 * [ok]=true; [ok]=false is reserved for real failures (auth, network, rejected push).
 */
@Serializable
data class BackupAckDto(val ok: Boolean, val head: String? = null, val noChange: Boolean = false)

/** Ack for POST /api/v1/sync/now. [conflicts] = merge conflicts surfaced (then visible via /conflicts). */
@Serializable
data class SyncAckDto(val ok: Boolean, val head: String? = null, val conflicts: Int? = null)

// ---- Agent management (GET/POST/PUT/DELETE /api/v1/agents) ----

@Serializable
data class AgentDto(
    val agentId: String,
    val name: String? = null,
    val role: String,
    val vaults: List<String> = emptyList(),
    /** The Secrets ref used to resolve this agent's bearer token (env:/file:/keychain:). Never the raw value. */
    val tokenRef: String,
    val prompt: String? = null,
)

@Serializable
data class AgentsDto(
    val agents: List<AgentDto>,
    val mcpPort: Int,
    /** MCP endpoint URL (http://<host>:<mcpPort>). Use for wiring agent clients. */
    val mcpUrl: String,
)

@Serializable
data class UpdateCheckDto(
    val currentVersion: String,
    val currentContract: String? = null,
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
    val compatible: Boolean = false,
    val assetName: String? = null,
    val assetUrl: String? = null,
    val sha256: String? = null,
    val notes: String? = null,
    val publishedAt: String? = null,
)

@Serializable
data class UpdateApplyDto(val started: Boolean, val candidateVersion: String? = null)

@Serializable
data class CreateAgentRequest(
    val agentId: String,
    val name: String? = null,
    val role: String,
    val vaults: List<String> = emptyList(),
    /** Secrets ref for the bearer token (env:/file:/keychain:) — raw tokens are rejected (422). */
    val tokenRef: String,
    val prompt: String? = null,
)

@Serializable
data class UpdateAgentRequest(
    val name: String? = null,
    val role: String? = null,
    val vaults: List<String>? = null,
    /** Secrets ref for the bearer token (env:/file:/keychain:) — raw tokens are rejected (422). */
    val tokenRef: String? = null,
    val prompt: String? = null,
)

// ---- People: App API principals (GET/POST/PUT/DELETE /api/v1/users, /me, /secrets — 0.30.0, ADR-0019) ----

/** One per-vault grant: `role` is `reader` | `editor`. */
@Serializable
data class VaultGrantDto(val vault: String, val role: String)

@Serializable
data class UserDto(
    val userId: String,
    val name: String,
    val email: String? = null,
    val admin: Boolean = false,
    val grants: List<VaultGrantDto> = emptyList(),
    /** The Secrets ref the key resolves through (file:/env:/keychain:). Never the key itself. */
    val keyRef: String,
    /** When this key last authenticated (ISO-8601 UTC); null if never, or unknown to this engine. */
    val lastUsedAt: String? = null,
)

@Serializable
data class UsersDto(val users: List<UserDto>)

@Serializable
data class CreateUserRequest(
    val userId: String,
    val name: String,
    val email: String? = null,
    val admin: Boolean = false,
    val grants: List<VaultGrantDto> = emptyList(),
)

@Serializable
data class UpdateUserRequest(
    val name: String? = null,
    val email: String? = null,
    val admin: Boolean? = null,
    val grants: List<VaultGrantDto>? = null,
)

/** The only response that ever carries the raw key — shown once, then gone. */
@Serializable
data class CreatedUserDto(val user: UserDto, val key: String)

@Serializable
data class RotatedKeyDto(val userId: String, val key: String)

/** Who am I: the app's connection test and the source of its per-vault read-only state. */
@Serializable
data class MeDto(
    val userId: String,
    val name: String,
    val admin: Boolean,
    /** True for the loopback UI identity (no key presented). */
    val local: Boolean,
    val grants: List<VaultGrantDto> = emptyList(),
    val lastUsedAt: String? = null,
)

@Serializable
data class CreateSecretRequest(val name: String, val value: String)

@Serializable
data class SecretRefDto(val ref: String)
