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
data class FileLinksDto(val path: String, val outlinks: List<OutlinkDto>, val backlinks: List<String>, val unresolved: List<String>)

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
data class ConflictEntryDto(val path: String, val reasons: List<String> = emptyList())

@Serializable
data class ConflictsDto(val conflicts: List<ConflictEntryDto>)

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
