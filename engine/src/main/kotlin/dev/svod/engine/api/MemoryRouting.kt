package dev.svod.engine.api

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the "recall" memory subsystem (the /api/v1/memory endpoints). Field names/shapes
 * mirror `contract/openapi.yaml`.
 *
 * Enum wire convention: `kind` ("skill"|"tool"), `scope` ("project"|"global") and proposal `status`
 * ("open"|"accepted"|"rejected") are LOWERCASE on the wire (both request and response), matching the
 * endpoint spec text. UI clients mirror these exact tokens.
 */

@Serializable
data class CaptureRequestDto(
    val sessionId: String,
    val project: String? = null,
    val transcript: String,
    val startedAt: Long,
    val endedAt: Long,
    val toolCallCount: Int? = null,
)

@Serializable
data class CaptureResultDto(
    val path: String,
    val revision: String,
    /** Nothing was written: the session is already stored with an equal or larger transcript. */
    val deduped: Boolean,
    /** An existing session note was rewritten with a larger transcript. Since 0.32.0. */
    val updated: Boolean = false,
)

@Serializable
data class SessionDto(
    val path: String,
    val project: String? = null,
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long,
    val bytes: Long,
    val distilled: Boolean,
)

@Serializable
data class MarkDistilledRequestDto(val paths: List<String>, val noteRefs: List<String> = emptyList())

@Serializable
data class MarkDistilledResultDto(val updated: Int)

@Serializable
data class ProposalDto(
    val id: String,
    val kind: String,
    val title: String,
    val scope: String,
    val confidence: Double,
    val rationale: String,
    val sourceSessions: List<String>,
    val createdAt: Long,
    val status: String,
    val note: String? = null,
)

@Serializable
data class CreateProposalRequestDto(
    val kind: String,
    val title: String,
    val scope: String,
    val confidence: Double,
    val rationale: String,
    val sourceSessions: List<String> = emptyList(),
)

@Serializable
data class CreateProposalResultDto(val id: String)

@Serializable
data class ProposalActionRequestDto(val action: String, val note: String? = null)

@Serializable
data class MemoryDashboardDto(
    val sessionsCaptured: Int,
    val sessionsDistilled: Int,
    val notesWritten: Int,
    val capturedBytes: Long,
    val distilledBytes: Long,
    val compressionRatio: Double,
    val lastDistillAt: Long? = null,
    val openProposals: Int,
    /** Memories in the review queue (same count as `GET /memory/review` `total`). Since 0.33.0. */
    val awaitingReview: Int,
)

@Serializable
data class MemoryReviewItemDto(
    val path: String,
    val title: String,
    val excerpt: String,
    val type: String? = null,
    val status: String? = null,
    val subject: String? = null,
    val confidence: Double? = null,
    val source: String? = null,
    val created: String? = null,
    val contradicts: String? = null,
    val supersedes: String? = null,
    val needsReview: Boolean,
    val revision: String,
)

@Serializable
data class MemoryReviewListDto(val total: Int, val items: List<MemoryReviewItemDto>)

@Serializable
data class MemoryReviewActionDto(val path: String, val action: String, val expectedRevision: String? = null)

@Serializable
data class MemoryReviewResultDto(val path: String, val revision: String, val commit: String, val status: String)

@Serializable
data class MemoryRulebookItemDto(val path: String, val title: String, val type: String, val subject: String? = null, val summary: String)

@Serializable
data class MemoryRulebookDto(val awaitingReview: Int, val items: List<MemoryRulebookItemDto>)
