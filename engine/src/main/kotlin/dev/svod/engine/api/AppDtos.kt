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
)

@Serializable
data class SearchResultDto(val mode: String, val hits: List<SearchHitDto>)

@Serializable
data class GraphNodeDto(val id: String, val path: String)

@Serializable
data class GraphEdgeDto(val source: String, val target: String)

@Serializable
data class GraphDto(val nodes: List<GraphNodeDto>, val edges: List<GraphEdgeDto>, val unresolved: List<GraphEdgeDto>)

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
)

@Serializable
data class IndexStatusDto(val docCount: Int, val headIndexed: String? = null, val model: String, val dim: Int)

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
data class RegisterSourceRequestDto(val path: String, val into: String? = null, val followSymlinks: Boolean = false)

@Serializable
data class ExternalSourceDto(
    val id: String,
    val path: String,
    val into: String,
    val followSymlinks: Boolean,
    val lastSyncedAt: String? = null,
)

@Serializable
data class SourceSyncResultDto(
    val id: String,
    val created: List<String> = emptyList(),
    val updated: List<String> = emptyList(),
    val unchanged: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val orphaned: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class VaultInfoDto(val id: String, val name: String, val default: Boolean, val sync: SyncStatusDto? = null)

@Serializable
data class VaultsDto(val vaults: List<VaultInfoDto>)

@Serializable
data class WriteStatsDto(val count: Long, val avgMs: Double, val maxMs: Double, val lastMs: Double)

@Serializable
data class IndexLagDto(val docCount: Int, val head: String? = null, val indexedHead: String? = null, val lagging: Boolean)

@Serializable
data class SyncStatusDto(val role: String, val lastHead: String? = null, val conflicts: Int)

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

/** Request to set a vault's backup destination. [remote] must be credential-free or a `Secrets` ref. */
@Serializable
data class BackupConfigRequestDto(val remote: String, val enabled: Boolean)

/** A vault's sync + backup configuration (GET /sync/config; also the body PUT /settings/backup returns). */
@Serializable
data class SyncConfigDto(
    val backupRemote: String? = null,
    val backupEnabled: Boolean = false,
    val syncPeers: List<String> = emptyList(),
    val role: String? = null,        // "authority" | "follower" | "solo"
    val hostId: String? = null,
)

/** Ack for POST /api/v1/maintenance/reindex (index self-heal from git HEAD). */
@Serializable
data class MaintenanceAckDto(val started: Boolean, val docCount: Int? = null)

/** Ack for POST /api/v1/backup/now. [head] = pushed commit sha, null when nothing was pushed. */
@Serializable
data class BackupAckDto(val ok: Boolean, val head: String? = null)

/** Ack for POST /api/v1/sync/now. [conflicts] = merge conflicts surfaced (then visible via /conflicts). */
@Serializable
data class SyncAckDto(val ok: Boolean, val head: String? = null, val conflicts: Int? = null)
