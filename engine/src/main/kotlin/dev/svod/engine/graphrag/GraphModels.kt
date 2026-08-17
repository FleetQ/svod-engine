package dev.svod.engine.graphrag

import kotlinx.serialization.Serializable

/**
 * Why an edge exists. Kept explicit (rather than folded into the weight) so the two sources can be
 * reasoned about — and tested — separately: [LINK] edges are human-authored and exact, [SIM] edges
 * are inferred and only as good as the embedder.
 */
enum class EdgeKind { LINK, SIM }

/** One undirected edge. [a] < [b] by string order, so an edge has exactly one representation. */
@Serializable
data class NoteEdge(val a: String, val b: String, val kind: EdgeKind, val weight: Double)

/** A community at one hierarchy level. [members] are note paths, sorted. */
@Serializable
data class Community(
    val id: String,
    val level: Int,
    val members: List<String>,
    /** LLM summary, or null when no summary provider was active (or the call failed). */
    val summary: String? = null,
    /** Human-readable label: the LLM's title, else a machine fallback derived from member paths. */
    val title: String,
    /** Model that produced [summary]; null when [summary] is null. */
    val model: String? = null,
    /**
     * Members attached incrementally AFTER this community was built and summarised.
     *
     * The summary is deliberately not regenerated for them — one note joining 320 does not
     * invalidate a two-sentence characterisation, and regenerating would put an LLM call back on the
     * commit path, which this feature exists to avoid. The count is the honest disclosure that the
     * summary describes slightly less than the membership does.
     */
    val addedSinceSummary: Int = 0,
) {
    val size: Int get() = members.size
}

/** One level of the Louvain hierarchy. Level 0 is finest. */
@Serializable
data class CommunityLevel(val level: Int, val communities: List<Community>)

/** Lifecycle of the sidecar build, surfaced exactly like `IndexService.embeddingStatus()`. */
enum class GraphState { NOT_BUILT, BUILDING, READY, ERROR }

/**
 * The language a community summary must be written in.
 *
 * Only two, and chosen by counting scripts rather than by asking the model: this vault is Bulgarian
 * and English, and a model left to decide for itself drifted to Chinese on a third of the corpus.
 */
enum class SummaryLanguage(val instruction: String, val systemClause: String) {
    BULGARIAN(
        instruction = "Пиши САМО на български език.",
        systemClause = "Answer only in Bulgarian. Never answer in any other language.",
    ),
    ENGLISH(
        instruction = "Пиши САМО на английски език.",
        systemClause = "Answer only in English. Never answer in any other language.",
    ),
}

/**
 * What the graph surfaces know about themselves. [stale] means the vault advanced past [head] since
 * the build — results are still served, because a slightly stale thematic map beats none, but the
 * caller is told. This is the deliberate answer to incremental staleness (design §8).
 */
@Serializable
data class GraphStatus(
    val state: String,
    val enabled: Boolean,
    val head: String? = null,
    val currentHead: String? = null,
    val stale: Boolean = false,
    val builtAt: Long? = null,
    val noteCount: Int = 0,
    val edgeCount: Int = 0,
    val linkEdgeCount: Int = 0,
    val simEdgeCount: Int = 0,
    val communityCount: Int = 0,
    val levelCount: Int = 0,
    /** Fraction of notes that had usable vectors at build time (0.0-1.0). Below 1.0 ⇒ LINK-heavy graph. */
    val vectorCoverage: Double = 0.0,
    val summaryProvider: String = "none",
    val summarisedCount: Int = 0,
    /**
     * Whether on-commit incremental attachment is switched on. Without it, [attachedCount] and
     * [pendingCount] are never computed and stay 0 — which would otherwise be indistinguishable from
     * "nothing has changed", so a client must read this flag before believing those two.
     */
    val incremental: Boolean = false,
    /** Notes attached to an existing community since the last full build (no Louvain, no LLM). */
    val attachedCount: Int = 0,
    /** Indexed notes that are on no theme at all: not yet attached, or with no close-enough neighbour. */
    val pendingCount: Int = 0,
    val error: String? = null,
    val progress: String? = null,
) {
    /** Notes that arrived after the last full build, attached or not — what a rebuild would fold in. */
    val newSinceBuild: Int get() = attachedCount + pendingCount
}

/** Persisted build metadata; the staleness comparison reads [head]. */
@Serializable
data class GraphMeta(
    val version: Int = SIDECAR_VERSION,
    val head: String?,
    val builtAt: Long,
    val noteCount: Int,
    val edgeCount: Int,
    val linkEdgeCount: Int,
    val simEdgeCount: Int,
    val vectorCoverage: Double,
    val summaryProvider: String,
    val summarisedCount: Int,
    /**
     * Notes attached incrementally since this build, in attachment order.
     *
     * Persisted (rather than derived) because it is the only record of which paths are already on the
     * map: they are added to community membership but deliberately NOT to [NoteGraph.nodes], so
     * without this list every restart would re-attach them and duplicate their membership. A full
     * rebuild resets it to empty, which is exactly right — the rebuild absorbs them properly.
     *
     * Defaulted, so a sidecar written before this field existed still loads: an older build simply
     * reports nothing attached, which is the truth for it.
     */
    val attachedPaths: List<String> = emptyList(),
) {
    companion object {
        /**
         * Bumped whenever the sidecar layout changes. A mismatch is treated as "never built" rather
         * than migrated — the sidecar is a derived cache, so rebuilding is always cheaper and safer
         * than writing migration code for it.
         */
        const val SIDECAR_VERSION = 1
    }
}

internal const val SIDECAR_VERSION = GraphMeta.SIDECAR_VERSION

/**
 * Member paths previewed per community when listing many of them.
 *
 * Measured on a 3,096-note vault: emitting every member made one default listing ≈44,300 tokens, of
 * which 97% were paths and ~1,200 were the titles and summaries a caller actually reasons over. Five
 * is enough to recognise a theme; the full list is one targeted call away.
 */
const val MEMBER_SAMPLE = 5
