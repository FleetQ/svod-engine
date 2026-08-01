package dev.svod.engine.memory

import dev.svod.engine.core.Revision
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.MarkdownChunker
import dev.svod.engine.index.SearchFilters
import dev.svod.engine.index.SearchQuery

/**
 * How an incoming memory relates to what is already stored.
 *
 * [UNCERTAIN] is a first-class answer, not a failure: when the evidence does not settle the
 * question the memory is still persisted, flagged for review, rather than guessed into one of the
 * confident classes.
 */
enum class Classification { NEW, DUPLICATE, CONTRADICTION, UPDATE, UNCERTAIN }

/**
 * Optional LLM adjudicator for the ambiguous middle band — the only place a model is consulted.
 *
 * Implementations are free to return null (unreachable, unparseable, or genuinely unsure); the
 * classifier then reports [Classification.UNCERTAIN]. No adjudicator configured ⇒ every ambiguous
 * case is UNCERTAIN, so the engine is fully functional with no LLM at all.
 */
interface MemoryAdjudicator {
    fun adjudicate(incoming: String, candidate: String, type: String): Verdict?

    data class Verdict(val classification: Classification, val confidence: Double, val rationale: String)
}

/** A stored memory considered as a comparison target, read at a specific [revision]. */
data class MemoryCandidate(
    val path: String,
    val revision: Revision,
    val body: String,
    val subject: String?,
)

/**
 * A classification decision plus everything needed to apply it safely later.
 *
 * [guards] is the exact state the verdict was computed against (path → revision). The apply step
 * re-checks it on the write-actor, so a candidate that changed in the meantime invalidates the plan
 * instead of producing a decision based on stale evidence.
 */
data class ClassificationPlan(
    val classification: Classification,
    val related: MemoryCandidate?,
    val confidence: Double,
    val rationale: String,
    val guards: Map<String, Revision>,
)

/**
 * Classifies an incoming memory against what is already stored, per-subject.
 *
 * Deliberately ordered cheapest-and-most-certain first:
 *  1. **normalized-text equality** — an exact restatement is a [Classification.DUPLICATE] with no
 *     model of any kind;
 *  2. **token overlap** (Jaccard) — a deterministic lexical signal that works with no embedder,
 *     which is the engine's guaranteed baseline;
 *  3. **embedding cosine** — used only when the configured [dev.svod.engine.index.Embedder] is
 *     active, and only to place the pair in a band;
 *  4. **LLM adjudication** — consulted ONLY for the ambiguous middle band, and only if configured.
 *
 * Everything here is impure and potentially slow (Lucene, a remote embedding call, an LLM call), so
 * it runs OFF the write-actor. The plan it returns is applied inside the actor by the caller.
 *
 * Scope is per-subject fact consistency, NOT entity resolution: candidates are restricted to the
 * same memory `type`, and to the same `subject` when the incoming memory declares one. Two facts
 * about different subjects are never compared, and no attempt is made to decide that two differently
 * spelled subjects denote the same entity.
 */
class FactClassifier(
    private val engine: SvodEngine,
    private val index: IndexService,
    private val adjudicator: MemoryAdjudicator? = null,
    /** How many retrieved candidates to consider (top-k over the existing hybrid RRF path). */
    private val topK: Int = 5,
) {

    suspend fun plan(content: String, type: String, subject: String?, targetPath: String): ClassificationPlan {
        val candidates = retrieve(content, type, subject, targetPath)
        val guards = candidates.associate { it.path to it.revision }
        if (candidates.isEmpty()) {
            return ClassificationPlan(Classification.NEW, null, 1.0, "no comparable memory of this type/subject", guards)
        }

        // 1. Deterministic: an exact restatement (modulo whitespace/case/punctuation) is a duplicate.
        val incomingNorm = normalize(content)
        candidates.firstOrNull { normalize(it.body) == incomingNorm }?.let {
            return ClassificationPlan(Classification.DUPLICATE, it, 1.0, "normalized text is identical", guards)
        }

        // 2. Deterministic: token overlap. Available with every provider, including none at all.
        val incomingTokens = tokenize(content)
        val byOverlap = candidates.maxByOrNull { jaccard(incomingTokens, tokenize(it.body)) }!!
        val overlap = jaccard(incomingTokens, tokenize(byOverlap.body))

        // 3. Embedding cosine, when (and only when) the configured embedder actually produces vectors.
        val embedder = index.activeEmbedder()
        val cosine: Pair<MemoryCandidate, Double>? = if (embedder.isActive) {
            runCatching {
                val vectors = embedder.embedPassages(listOf(content) + candidates.map { it.body })
                val incomingVec = vectors.first()
                candidates.zip(vectors.drop(1))
                    .map { (c, v) -> c to cosine(incomingVec, v) }
                    .maxByOrNull { it.second }
            }.getOrNull()
        } else null

        if (cosine != null) {
            val (candidate, score) = cosine
            return when {
                score >= DUPLICATE_COSINE ->
                    ClassificationPlan(Classification.DUPLICATE, candidate, score, "embedding cosine %.3f ≥ %.2f".format(score, DUPLICATE_COSINE), guards)
                score >= AMBIGUOUS_COSINE ->
                    adjudicated(content, candidate, type, guards, "embedding cosine %.3f in the ambiguous band".format(score))
                else ->
                    ClassificationPlan(Classification.NEW, null, 1.0 - score, "embedding cosine %.3f < %.2f — unrelated".format(score, AMBIGUOUS_COSINE), guards)
            }
        }

        // 4. No vectors available: fall back to the lexical signal alone.
        return when {
            overlap >= DUPLICATE_OVERLAP ->
                ClassificationPlan(Classification.DUPLICATE, byOverlap, overlap, "token overlap %.2f ≥ %.2f (no embedder)".format(overlap, DUPLICATE_OVERLAP), guards)
            overlap >= AMBIGUOUS_OVERLAP ->
                adjudicated(content, byOverlap, type, guards, "token overlap %.2f in the ambiguous band (no embedder)".format(overlap))
            else ->
                ClassificationPlan(Classification.NEW, null, 1.0 - overlap, "token overlap %.2f < %.2f — unrelated".format(overlap, AMBIGUOUS_OVERLAP), guards)
        }
    }

    /** The ambiguous band: ask the LLM if there is one, otherwise say so honestly. */
    private fun adjudicated(
        content: String,
        candidate: MemoryCandidate,
        type: String,
        guards: Map<String, Revision>,
        signal: String,
    ): ClassificationPlan {
        val verdict = adjudicator?.let { runCatching { it.adjudicate(content, candidate.body, type) }.getOrNull() }
            ?: return ClassificationPlan(
                Classification.UNCERTAIN, candidate, 0.0,
                "$signal; no LLM adjudicator available", guards,
            )
        // An adjudicator may itself decline to commit — that stays UNCERTAIN.
        return ClassificationPlan(verdict.classification, candidate, verdict.confidence, "$signal; ${verdict.rationale}", guards)
    }

    /**
     * Top-k comparable memories via the EXISTING hybrid path (BM25 + kNN + RRF) — no new index.
     *
     * `includeAll` is required: memory facts enter `provisional` and are hidden from normal recall,
     * yet they are exactly what an incoming fact must be checked against. Retired memories (revoked
     * or already superseded) are dropped — they cannot be contradicted. Subject scoping is applied
     * here, after retrieval, because `subject` is note frontmatter and is not an indexed field.
     */
    private suspend fun retrieve(content: String, type: String, subject: String?, targetPath: String): List<MemoryCandidate> {
        val hits = index.search(
            SearchQuery(
                text = content,
                filters = SearchFilters(type = type, includeAll = true),
                limit = topK,
            ),
        ).hits
        val out = LinkedHashMap<String, MemoryCandidate>()
        for (path in hits.map { it.path }.distinct()) {
            if (path == targetPath || out.size >= topK) continue
            val file = engine.read(path) ?: continue
            val parsed = MarkdownChunker.parse(file.text)
            if (parsed.status == "revoked" || parsed.supersededBy != null) continue
            val candidateSubject = parsed.frontmatter["subject"]?.toString()
            if (subject != null && !candidateSubject.equals(subject, ignoreCase = true)) continue
            out[path] = MemoryCandidate(path, file.revision, parsed.body.trim(), candidateSubject)
        }
        return out.values.toList()
    }

    companion object {
        /** At or above this cosine, two memories are treated as the same statement. */
        const val DUPLICATE_COSINE = 0.97

        /** Below this cosine the memories are unrelated; between the two is the ambiguous band. */
        const val AMBIGUOUS_COSINE = 0.82

        /** Lexical equivalents of the cosine bands, used when no embedder is active. */
        const val DUPLICATE_OVERLAP = 0.90
        const val AMBIGUOUS_OVERLAP = 0.35

        /** Case-folded, punctuation-stripped, whitespace-collapsed form used for exact-duplicate equality. */
        internal fun normalize(text: String): String =
            text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        internal fun tokenize(text: String): Set<String> =
            normalize(text).split(' ').filter { it.isNotEmpty() }.toSet()

        internal fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            val union = a.size + b.size - a.count { it in b }
            return if (union == 0) 0.0 else a.count { it in b }.toDouble() / union
        }

        internal fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size || a.isEmpty()) return 0.0
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i]
                na += a[i].toDouble() * a[i]
                nb += b[i].toDouble() * b[i]
            }
            return if (na == 0.0 || nb == 0.0) 0.0 else dot / (Math.sqrt(na) * Math.sqrt(nb))
        }
    }
}
