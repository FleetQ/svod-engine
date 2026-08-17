package dev.svod.engine.graphrag

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.graph.LinkIndex
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.MarkdownChunker
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** Runtime configuration for the graph sidecar, resolved from `SvodConfig.GraphSettings`. */
data class GraphConfig(
    val enabled: Boolean = false,
    val simEdgesPerNote: Int = 6,
    val simThreshold: Double = 0.75,
    val minCommunitySize: Int = 3,
    /** How many of the COARSEST levels get LLM summaries. Bounds the call count (see [summarise]). */
    val summariseTopLevels: Int = 1,
    val summaryInputChars: Int = 12_000,
    val rebuildOnStartup: Boolean = false,
    val summary: SummaryLlmConfig = SummaryLlmConfig(),
)

/**
 * Owns the derived graph for one vault: background build, sidecar persistence, status, and the
 * LLM-free query surface.
 *
 * **Isolation is the design constraint.** Nothing here may make `search` or `context_pack` fail, slow
 * down, or change results (tests A2/A3/A5). The build runs on its own MIN_PRIORITY daemon thread —
 * mirroring `svod-index-boot` — reads the Lucene index but never writes it, and every public method
 * returns a value rather than throwing.
 */
class GraphService(
    dir: Path,
    private val engine: SvodEngine,
    private val index: IndexService,
    private val summaryLlm: SummaryLlm,
    private val config: GraphConfig,
    private val detector: CommunityDetector = LouvainDetector(),
) : AutoCloseable {

    private val store = GraphStore(dir)

    @Volatile private var state: GraphState = GraphState.NOT_BUILT
    @Volatile private var loaded: GraphStore.Loaded? = null
    @Volatile private var errorMessage: String? = null
    @Volatile private var progress: String? = null
    @Volatile private var buildThread: Thread? = null
    @Volatile private var cancelled = false

    /** Loads any existing sidecar and, if configured, kicks off a background build. Never blocks. */
    fun start(): GraphService {
        if (!config.enabled) return this
        loaded = store.load()?.also { state = GraphState.READY }
        if (config.rebuildOnStartup && (loaded == null || isStale())) rebuild()
        return this
    }

    /** Starts a background rebuild. No-op when disabled or already building. */
    fun rebuild(): Boolean {
        if (!config.enabled) return false
        synchronized(this) {
            if (buildThread?.isAlive == true) return false
            cancelled = false
            state = GraphState.BUILDING
            errorMessage = null
            progress = "starting"
            buildThread = Thread({
                // The terminal state is assigned in exactly ONE place, structurally. runBuild has
                // several `if (cancelled) return` checkpoints; if any of them could skip the state
                // assignment the status would wedge at BUILDING for the life of the service — a
                // permanently spinning UI and a `rebuild()` that keeps refusing.
                val terminal = try {
                    if (runBuild()) GraphState.READY else settledState()
                } catch (t: Throwable) {
                    // Contained: a graph build failure must never surface as a broken engine (test A5).
                    errorMessage = t.message ?: t::class.simpleName
                    System.err.println("graph build failed: ${t.message}")
                    GraphState.ERROR
                }
                progress = null
                state = terminal
            }, "svod-graph-build").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            buildThread?.start()
        }
        return true
    }

    /** What to report when a build ended without completing: whatever we can still serve. */
    private fun settledState(): GraphState =
        if (loaded != null) GraphState.READY else GraphState.NOT_BUILT

    /**
     * Cancels an in-flight build WITHOUT interrupting its thread.
     *
     * Separate from [close] so the cooperative-cancellation path (the `if (cancelled) return`
     * checkpoints) is reachable deterministically — interrupting instead raises through the catch,
     * which is a different path and would leave the early-return checkpoints untested.
     */
    internal fun cancelBuild() {
        cancelled = true
    }

    /** @return true when the build ran to completion; false when it was cancelled part-way. */
    private fun runBuild(): Boolean {
        // The index head, not `engine.head()`: the graph is a function of the INDEX (its paths and
        // its vectors), so that is the head staleness must be measured against. It is also a plain
        // file read, which keeps `status()` off the write actor and off any blocking call — see
        // [currentHead].
        val head = index.headCommitIndexed()
        val paths = index.indexedPaths()

        progress = "link graph"
        val linkGraph = LinkIndex(engine.root).use { it.graph() }

        val built = NoteGraphBuilder(index, config.simEdgesPerNote, config.simThreshold)
            .build(linkGraph, paths, onProgress = { progress = it }, isCancelled = { cancelled })
        if (cancelled) return false

        progress = "communities"
        val partitions = detector.detect(built.graph)
        if (cancelled) return false

        val centroids = HashMap<String, FloatArray>()
        val levels = partitions.mapIndexed { levelIdx, partition ->
            CommunityLevel(
                level = levelIdx,
                communities = disambiguate(
                    partition.mapIndexed { i, members ->
                        val id = "L$levelIdx-$i"
                        centroid(members, built.vectors)?.let { centroids[id] = it }
                        Community(id = id, level = levelIdx, members = members, title = fallbackTitle(members))
                    },
                ),
            )
        }

        val summarised = summarise(levels)
        if (cancelled) return false

        val meta = GraphMeta(
            head = head,
            builtAt = System.currentTimeMillis(),
            noteCount = built.graph.nodes.size,
            edgeCount = built.graph.edges.size,
            linkEdgeCount = built.graph.linkEdgeCount,
            simEdgeCount = built.graph.simEdgeCount,
            vectorCoverage = built.vectorCoverage,
            summaryProvider = summaryLlm.provider,
            summarisedCount = summarised.sumOf { lvl -> lvl.communities.count { it.summary != null } },
        )
        store.save(meta, built.graph, summarised, centroids)
        loaded = GraphStore.Loaded(meta, built.graph, summarised, centroids)
        return true
    }

    /**
     * Attaches LLM summaries to the coarsest [GraphConfig.summariseTopLevels] levels only.
     *
     * This is what keeps the call count in the tens rather than the thousands: summarising every
     * community at every level of a 5-level hierarchy would be an order of magnitude more calls for
     * levels a user rarely opens. Finer levels keep their machine-derived titles.
     */
    private fun summarise(levels: List<CommunityLevel>): List<CommunityLevel> {
        if (!summaryLlm.isActive || levels.isEmpty()) return levels
        val firstSummarised = (levels.size - config.summariseTopLevels).coerceAtLeast(0)
        var done = 0
        val out = ArrayList<CommunityLevel>(levels.size)
        for (lvl in levels) {
            if (lvl.level < firstSummarised) {
                out.add(lvl)
                continue
            }
            val communities = ArrayList<Community>(lvl.communities.size)
            for (c in lvl.communities) {
                if (cancelled || c.size < config.minCommunitySize) {
                    communities.add(c)
                    continue
                }
                progress = "summarising ${++done}"
                // [SummaryLlm] documents that summarise must not throw, but it is a pluggable
                // interface — trusting that would let one bad implementation fail a whole build for
                // an optional embellishment. Contain it here (test D5).
                // buildPrompt returns the language it decided, so the system clause carries the SAME
                // decision. Re-deriving it from the assembled prompt would read this file's own
                // Cyrillic instruction text as content and classify everything as Bulgarian.
                val built = buildPrompt(c)
                val raw = runCatching {
                    runBlocking { summaryLlm.summarise(built.prompt, systemPrompt(built.language)) }
                }
                    .onFailure { System.err.println("graph summary: provider threw: ${it.message}") }
                    .getOrNull()
                if (raw.isNullOrBlank()) {
                    communities.add(c)
                } else {
                    val (title, body) = parseSummary(raw)
                    communities.add(c.copy(title = title ?: c.title, summary = body, model = summaryLlm.model))
                }
            }
            out.add(lvl.copy(communities = communities))
        }
        return out
    }

    /**
     * Builds the summary prompt from member notes.
     *
     * **Binding privacy constraint:** content is read through [MarkdownChunker.stripPrivateSpans],
     * exactly as `context_pack` does, and only for paths that are in the index — a `private: true`
     * note is never indexed, so it can never become a graph node and can never reach a model
     * (test D2). Input is capped so a large community cannot overrun the model's context (test D4).
     */
    /** A prompt plus the language decision that produced it, so both travel together. */
    internal data class BuiltPrompt(val prompt: String, val language: SummaryLanguage)

    private fun buildPrompt(c: Community): BuiltPrompt {
        val sb = StringBuilder()
        val header = StringBuilder()
        val body = StringBuilder()
        val budget = config.summaryInputChars

        var included = 0
        for (p in c.members) {
            if (body.length >= budget) break
            val text = runCatching {
                runBlocking { engine.read(p) }?.text?.let(MarkdownChunker::stripPrivateSpans)
            }.getOrNull() ?: continue
            val remaining = (budget - body.length).coerceAtLeast(0)
            if (remaining < 80) break
            body.append("--- ").append(p).append('\n')
            body.append(text.take(remaining.coerceAtMost(PER_NOTE_CHARS))).append("\n\n")
            included++
        }

        header.append("Групa от ${c.size} бележки. Пътища: ")
            .append(c.members.take(PATH_LIST_CAP).joinToString(", "))
            .append(if (c.size > PATH_LIST_CAP) ", …\n\n" else "\n\n")
            .append("=== НАЧАЛО НА ИЗВАДКАТА ===\n")

        val footer = StringBuilder("=== КРАЙ НА ИЗВАДКАТА ===\n\n")
        // Real communities reach hundreds of notes while the prompt budget fits under ten, so the
        // model MUST be told it is seeing a sample. Without this it confidently describes 588 notes
        // from 8 — a fabrication the operator has no way to detect.
        if (included < c.size) {
            footer.append(
                "Видя само $included от общо ${c.size} бележки. Обобщи какво личи от извадката и не " +
                    "твърди, че описваш всички.\n",
            )
        }
        // The instruction goes AFTER the excerpts, not before them. Measured on the real vault with
        // qwen2.5:7b: with the instruction first, 6 of 21 communities came back as a verbatim
        // continuation of the pasted documents — 12k characters of source text drowned a leading
        // instruction and the model simply kept writing the document. Recency fixes that.
        val language = dominantLanguage(body)
        footer.append(
            "Горното са ИЗВАДКИ, които трябва да обобщиш. Не ги преразказвай дословно и не " +
                "продължавай текста им.\n" +
                "Отговори с точно два реда и нищо друго, без форматиране, без звездички:\n" +
                "TITLE: <кратко заглавие до 6 думи>\n" +
                "SUMMARY: <2-4 изречения какво обединява тези бележки>\n" +
                language.instruction + " Не измисляй факти.",
        )

        sb.append(header).append(body).append(footer)
        return BuiltPrompt(sb.toString(), language)
    }

    /**
     * The language the answer must be written in, decided HERE rather than by the model.
     *
     * "Write in the language predominant in the notes" asks a 7B model to make a judgement it does
     * not make reliably: with that instruction, `qwen2.5:7b` (a Chinese-origin model) answered in
     * Chinese for **8 of 21** real communities whose notes contain no Chinese at all. Counting the
     * scripts ourselves and issuing one unambiguous instruction removes the judgement entirely.
     */
    internal fun dominantLanguage(text: CharSequence): SummaryLanguage {
        var cyrillic = 0
        var latin = 0
        for (ch in text) {
            when {
                ch in 'Ѐ'..'ӿ' -> cyrillic++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
            }
        }
        return if (cyrillic > latin) SummaryLanguage.BULGARIAN else SummaryLanguage.ENGLISH
    }

    /** System instruction, kept out of the prompt so it cannot be mistaken for input. */
    private fun systemPrompt(language: SummaryLanguage): String =
        "You summarise groups of notes. Always answer with exactly two lines, 'TITLE: ...' and " +
            "'SUMMARY: ...'. Never copy or continue the supplied text. ${language.systemClause}"

    /**
     * Parses the `TITLE:` / `SUMMARY:` shape, tolerating a model that decorates it.
     *
     * The first version anchored on `^\s*TITLE:` and missed every response that came back as
     * `**TITLE: …**`, which qwen2.5:7b produced for 2 of 21 real communities — the label then leaked
     * into the summary text and the title silently fell back to a folder name. Markdown emphasis,
     * list bullets and quote markers are all stripped before matching, and the trailing emphasis is
     * removed from the captured value.
     */
    private fun parseSummary(raw: String): Pair<String?, String> {
        val title = LABEL_TITLE.find(raw)?.groupValues?.get(1)?.let(::cleanLabelValue)?.takeIf { it.isNotEmpty() }
        val body = LABEL_SUMMARY.find(raw)?.groupValues?.get(1)?.let(::cleanLabelValue)?.takeIf { it.isNotEmpty() }
        return title to (body ?: raw.trim())
    }

    /** Strips the markdown decoration a model wraps around a labelled value. */
    private fun cleanLabelValue(v: String): String =
        v.trim().trim('*', '_', '`', '"', '\'', ' ').trim()

    /**
     * Machine label used when there is no summary: the most specific directory prefix that still
     * covers at least half the members.
     *
     * The naive version (immediate parent, else the first member's basename) produced labels like
     * "SKILL" and "b42bbb8d6b01" on the real vault — the first member's filename, which says nothing
     * about the other 587. Scoring by depth × coverage instead yields "ai-prompts/03-custom-skills",
     * which is an actual description of the group.
     */
    private fun fallbackTitle(members: List<String>): String {
        val counts = HashMap<String, Int>()
        for (m in members) {
            val parts = m.split('/').dropLast(1)
            var prefix = ""
            for (p in parts) {
                prefix = if (prefix.isEmpty()) p else "$prefix/$p"
                counts.merge(prefix, 1, Int::plus)
            }
        }
        val half = (members.size + 1) / 2
        val best = counts.entries
            .filter { it.value >= half }
            // Deepest wins; ties by coverage, then lexicographically so the label is deterministic.
            .maxWithOrNull(
                compareBy<Map.Entry<String, Int>> { it.key.count { c -> c == '/' } }
                    .thenBy { it.value }
                    .thenByDescending { it.key },
            )
        if (best != null) return best.key
        // Nothing shared by half the members: name it after the biggest single folder present.
        val topDir = members.mapNotNull { it.substringBeforeLast('/', "").takeIf { d -> d.isNotEmpty() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return topDir ?: members.first().substringAfterLast('/').removeSuffix(".md")
    }

    /**
     * Makes labels unique within a level.
     *
     * On the real vault, four separate communities all fell back to "projects" — a list of identical
     * rows the operator cannot choose between. Since the default config has NO summary provider, this
     * is the DEFAULT rendering, not an edge case, so the fallback has to be usable on its own.
     * Colliding titles are extended by the most common next path segment, then by size rank if that
     * still is not enough.
     */
    private fun disambiguate(communities: List<Community>): List<Community> {
        val byTitle = communities.groupBy { it.title }
        if (byTitle.values.all { it.size == 1 }) return communities

        // Pass 1: extend a colliding label with its own most common next path segments.
        val extended = communities.map { c ->
            if (byTitle.getValue(c.title).size == 1) return@map c
            val depth = c.title.count { it == '/' } + 1
            val segments = c.members
                .mapNotNull { it.split('/').getOrNull(depth)?.removeSuffix(".md") }
                .groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(2).map { it.key }
            if (segments.isEmpty()) c else c.copy(title = "${c.title}/${segments.joinToString(", ")}…")
        }

        // Pass 2: two communities can still share their top segments (seen on the real vault — two
        // groups both dominated by `projects/skb2`). The id is unique and deterministic, so it is the
        // safe last resort; a duplicated row in the UI would be worse than an opaque suffix.
        val byExtended = extended.groupBy { it.title }
        return extended.map { c -> if (byExtended.getValue(c.title).size == 1) c else c.copy(title = "${c.title} (${c.id})") }
    }

    private fun centroid(members: List<String>, vectors: Map<String, FloatArray>): FloatArray? {
        val vs = members.mapNotNull { vectors[it] }
        if (vs.isEmpty()) return null
        val dim = vs.first().size
        val sum = DoubleArray(dim)
        for (v in vs) if (v.size == dim) for (i in 0 until dim) sum[i] += v[i]
        var norm = 0.0
        for (x in sum) norm += x * x
        norm = kotlin.math.sqrt(norm)
        if (norm == 0.0) return null
        return FloatArray(dim) { (sum[it] / norm).toFloat() }
    }

    // ---- query surface (NO LLM — guarded by test D1) ----

    /**
     * Communities at [level] (default coarsest). With a [query], ranked by cosine between the query
     * embedding and each community centroid; without one, deterministic by size then id.
     *
     * Returns evidence, never a synthesised answer — the caller does the reasoning. That is what keeps
     * query time free of any model call beyond the embedding the search path already makes.
     */
    fun communities(query: String? = null, level: Int? = null, limit: Int = 20): List<Community> {
        val snapshot = loaded ?: return emptyList()
        if (snapshot.levels.isEmpty()) return emptyList()
        val idx = (level ?: snapshot.levels.lastIndex).coerceIn(0, snapshot.levels.lastIndex)
        val all = snapshot.levels[idx].communities

        if (query.isNullOrBlank()) return all.take(limit)

        val embedder = index.activeEmbedder()
        if (!embedder.isActive) return all.take(limit)
        val qv = runCatching { embedder.embedQuery(query) }.getOrNull() ?: return all.take(limit)

        return all.mapNotNull { c ->
            val centroid = snapshot.centroids[c.id] ?: return@mapNotNull null
            if (centroid.size != qv.size) return@mapNotNull null
            var dot = 0.0
            for (i in qv.indices) dot += qv[i] * centroid[i]
            c to dot
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** Members of one community, for the UI's "open a note from this theme" path. */
    fun community(id: String): Community? =
        loaded?.levels?.firstNotNullOfOrNull { lvl -> lvl.communities.firstOrNull { it.id == id } }

    /**
     * The head to compare against. Deliberately [IndexService.headCommitIndexed] rather than
     * `engine.head()`: the latter is a suspend call onto the write actor, and [status] is invoked
     * from App API request handlers — wrapping it in `runBlocking` would be the only blocking call
     * this feature puts on the request path, which is exactly what this sprint promised not to do.
     */
    private fun currentHead(): String? = runCatching { index.headCommitIndexed() }.getOrNull()

    fun status(): GraphStatus {
        val snapshot = loaded
        val currentHead = currentHead()
        return GraphStatus(
            state = state.name,
            enabled = config.enabled,
            head = snapshot?.meta?.head,
            currentHead = currentHead,
            stale = snapshot != null && currentHead != null && snapshot.meta.head != currentHead,
            builtAt = snapshot?.meta?.builtAt,
            noteCount = snapshot?.meta?.noteCount ?: 0,
            edgeCount = snapshot?.meta?.edgeCount ?: 0,
            linkEdgeCount = snapshot?.meta?.linkEdgeCount ?: 0,
            simEdgeCount = snapshot?.meta?.simEdgeCount ?: 0,
            communityCount = snapshot?.levels?.sumOf { it.communities.size } ?: 0,
            levelCount = snapshot?.levels?.size ?: 0,
            vectorCoverage = snapshot?.meta?.vectorCoverage ?: 0.0,
            summaryProvider = summaryLlm.provider,
            summarisedCount = snapshot?.meta?.summarisedCount ?: 0,
            error = errorMessage,
            progress = progress,
        )
    }

    private fun isStale(): Boolean {
        val snapshot = loaded ?: return true
        val current = currentHead() ?: return false
        return snapshot.meta.head != current
    }

    override fun close() {
        cancelled = true
        buildThread?.interrupt()
    }

    private companion object {
        /** Per-note excerpt cap, so one long note cannot consume a whole community's prompt budget. */
        const val PER_NOTE_CHARS = 1_500

        /**
         * How many member paths are listed verbatim in the prompt. Paths are cheap and carry real
         * signal about a group's shape, so a large community still gets a usable outline even when
         * only a handful of bodies fit.
         */
        const val PATH_LIST_CAP = 60

        /**
         * Label matchers. `[^\p{L}\p{N}]{0,4}` absorbs the `**`, `- `, `> ` and `#` a model puts in
         * front of a label; without it a bolded `**TITLE:` never matches at all.
         */
        val LABEL_TITLE = Regex("(?im)^[^\\p{L}\\p{N}]{0,4}TITLE\\s*:\\s*(.+)$")
        val LABEL_SUMMARY = Regex("(?ims)^[^\\p{L}\\p{N}]{0,4}SUMMARY\\s*:\\s*(.+)$")
    }
}
