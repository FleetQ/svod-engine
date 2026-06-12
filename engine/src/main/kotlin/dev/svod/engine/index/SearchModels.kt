package dev.svod.engine.index

/** Filters applied to both the BM25 and kNN legs before fusion. */
data class SearchFilters(
    val tags: List<String> = emptyList(),
    /** Match files whose path starts with this prefix (e.g. "vault/" or "notes/"). */
    val pathPrefix: String? = null,
    /** Inclusive epoch-second bounds on the document's `created` frontmatter. */
    val createdFrom: Long? = null,
    val createdTo: Long? = null,
) {
    val isEmpty: Boolean
        get() = tags.isEmpty() && pathPrefix == null && createdFrom == null && createdTo == null
}

enum class SearchMode { HYBRID, KEYWORD, SEMANTIC }

data class SearchQuery(
    val text: String,
    val filters: SearchFilters = SearchFilters(),
    val mode: SearchMode = SearchMode.HYBRID,
    val limit: Int = 10,
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
)
