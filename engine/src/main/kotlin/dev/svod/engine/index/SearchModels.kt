package dev.svod.engine.index

/** Filters applied to both the BM25 and kNN legs before fusion. */
data class SearchFilters(
    val tags: List<String> = emptyList(),
    /** Match files whose path starts with this prefix (e.g. "vault/" or "notes/"). */
    val pathPrefix: String? = null,
    /** Inclusive epoch-second bounds on the document's `created` frontmatter. */
    val createdFrom: Long? = null,
    val createdTo: Long? = null,
    /** Memory typing: match only notes with this frontmatter `type` (policy/preference/fact/episode/…). */
    val type: String? = null,
    /** Match only notes with this frontmatter `status`; overrides the default lifecycle hiding for it. */
    val status: String? = null,
    /**
     * Bypass the default lifecycle hiding (revoked / provisional / superseded / expired). Off by
     * default so recall stays clean; the UI sets it to manage/inspect all memories.
     */
    val includeAll: Boolean = false,
) {
    /** True when no USER-facing filter is set. (The default lifecycle hiding is applied regardless,
     *  inside the index, so a blank-query browse-by-filter guard still works off this.) */
    val isEmpty: Boolean
        get() = tags.isEmpty() && pathPrefix == null && createdFrom == null && createdTo == null && type == null && status == null
}

enum class SearchMode { HYBRID, KEYWORD, SEMANTIC }

data class SearchQuery(
    val text: String,
    val filters: SearchFilters = SearchFilters(),
    val mode: SearchMode = SearchMode.HYBRID,
    val limit: Int = 10,
    /**
     * Point-in-time recall: when set (epoch seconds), answer from the last commit on HEAD's
     * first-parent line at or before this time — keyword-only, lifecycle judged at this time.
     */
    val asOfEpoch: Long? = null,
)

data class SearchHit(
    val chunkId: String,
    val path: String,
    val heading: String,
    val snippet: String,
    val tags: List<String>,
    /** Fused relevance score (RRF for HYBRID; raw leg score otherwise). Higher = better. */
    val score: Double,
    /** Which retrieval signals matched this hit. */
    val matchedKeyword: Boolean,
    val matchedSemantic: Boolean,
)

data class SearchResult(
    val hits: List<SearchHit>,
    val mode: SearchMode,
    val tookMillis: Long,
    /**
     * Retrieval legs this result had to go without: [SEMANTIC] (asked for, embedder configured, but the
     * query embed failed or semantic is suppressed during a model rebuild — any hits are then
     * keyword-only; a SEMANTIC search whose embed failed has none) and/or [RERANK] (active reranker
     * failed). Empty for a complete result.
     */
    val degraded: List<String> = emptyList(),
    /** For an as-of query ([SearchQuery.asOfEpoch]): the commit answered from; null when none existed then. */
    val asOfCommit: String? = null,
    /**
     * Matching notes the default lifecycle hiding removed, as reason → count of distinct notes
     * ([WITHHELD_REVOKED] / [WITHHELD_SUPERSEDED] / [WITHHELD_EXPIRED] / [WITHHELD_PROVISIONAL], in
     * that order; a note is counted once, under the first reason that applies). Keyword match only;
     * session transcripts are never counted, `messy/` drafts only when the search itself would include
     * them. Empty when nothing was hidden, for
     * an includeAll search and for an as-of search.
     */
    val withheld: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val SEMANTIC = "semantic"
        const val RERANK = "rerank"
        const val WITHHELD_REVOKED = "revoked"
        const val WITHHELD_SUPERSEDED = "superseded"
        const val WITHHELD_EXPIRED = "expired"
        const val WITHHELD_PROVISIONAL = "provisional"

        /** The withheld reasons in their fixed precedence / reporting order. */
        val WITHHELD_REASONS = listOf(WITHHELD_REVOKED, WITHHELD_SUPERSEDED, WITHHELD_EXPIRED, WITHHELD_PROVISIONAL)
    }
}
