package dev.svod.engine.index

import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.eclipse.jgit.diff.DiffEntry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the index and keeps it an exact function of git HEAD.
 *
 * All Lucene mutations run on a single dedicated thread ([exec]), entirely OFF the engine's write
 * path: the engine calls [onCommit] after each commit, which only enqueues a sync and returns
 * immediately — writes never wait on embedding or Lucene I/O. Reads (search) are thread-safe and
 * need no coordination.
 *
 * Startup is non-blocking (unless [blockStartup]): [start] returns as soon as the boot job is
 * scheduled. The boot job is **keyword-first** — a fast BM25 pass makes lexical search available
 * within seconds — then a **throttled, resumable background pass** fills in embeddings. The
 * embedding pass is capped at [maxThreads] low-priority workers, batches its calls by [batchSize],
 * commits periodically (each commit is a resume checkpoint), and never saturates the machine.
 * Because progress is committed to Lucene and the backlog is recomputed from the index
 * ([LuceneIndex.pathsMissingVectors]), an interrupted run resumes instead of restarting.
 *
 * Reconciliation triggers:
 *  - boot with an incompatible model/dim/schema ⇒ full reindex (migration)
 *  - [onCommit] / [sync] ⇒ incremental diff from the last indexed commit (embeds inline)
 *  - [reconcileNow] ⇒ full HEAD compare (self-heal after drift or an index wipe)
 *  - [reembed] / [setEmbedder] ⇒ background re-embed (provider/model swap)
 */
class IndexService(
    private val root: Path,
    private val indexDir: Path,
    embedder: Embedder,
    private val schemaVersion: Int = IndexMeta.SCHEMA_VERSION,
    /** Block [start] until the FULL index (incl. embeddings) is ready. Default true (tests/simple). */
    private val blockStartup: Boolean = true,
    /** Cap of concurrent low-priority embedding workers (background pass). */
    private val maxThreads: Int = 2,
    /** Max texts per embedder call (background pass). */
    private val batchSize: Int = 32,
    /** Optional second-stage reranker over the fused candidates. Default [NoneReranker] ⇒ no reranking. */
    reranker: Reranker = NoneReranker,
    /** How many top fused candidates the reranker re-scores per query (the rest keep fused order). */
    private val rerankTopK: Int = 50,
) : AutoCloseable {

    /** The active embedder. Swappable at runtime via [setEmbedder] (provider change). */
    @Volatile
    private var embedder: Embedder = embedder

    /** The active reranker (opt-in second stage). */
    @Volatile
    private var reranker: Reranker = reranker

    private val log = org.slf4j.LoggerFactory.getLogger(IndexService::class.java)
    private val index = LuceneIndex(indexDir)
    private val reader = GitReader(root)
    private val metaFile = indexDir.resolve("meta.json")
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "svod-indexer").apply { isDaemon = true } }

    // ---- background embedding state (surfaced via index/status + index.progress) ----

    enum class EmbeddingState { IDLE, RUNNING, PAUSED, ERROR }

    data class EmbeddingStatus(
        val state: EmbeddingState,
        val done: Int,
        val total: Int,
        val model: String,
        val error: String?,
        /** Rolling embed throughput (chunks/sec) while a pass runs; null when idle or not yet measurable. */
        val ratePerSec: Double? = null,
        /** Estimated seconds until the current pass finishes; null when idle or not yet measurable. */
        val etaSeconds: Long? = null,
    )

    private val keywordReadyFlag = AtomicBoolean(false)
    private val embeddingState = AtomicReference(EmbeddingState.IDLE)
    private val embeddingDone = AtomicInteger(0)
    private val embeddingTotal = AtomicInteger(0)
    @Volatile private var embeddingError: String? = null

    // While a provider/model swap rebuilds vectors, keep semantic OFF so we never serve stale
    // (wrong-model) or partial vectors — keyword search stays fully available throughout.
    @Volatile private var suppressSemantic = false

    private val closing = AtomicBoolean(false)
    @Volatile private var bootThread: Thread? = null

    // Pause/resume for the background embedding pass.
    private val pauseLock = ReentrantLock()
    private val pauseCond = pauseLock.newCondition()
    @Volatile private var paused = false

    private var lastProgressEmit = 0L

    // Throughput tracking for the ETA: when the current pass entered RUNNING and the done-count then.
    @Volatile private var passStartMs = 0L
    @Volatile private var passStartDone = 0

    /** Invoked on the indexer thread after the index advances to a new HEAD (drives index.updated). */
    @Volatile
    var onSynced: ((headCommit: String?) -> Unit)? = null

    /** Throttled embedding progress (done,total,state) — drives the index.progress WS event. */
    @Volatile
    var onProgress: ((done: Int, total: Int, state: String) -> Unit)? = null

    /** True once the BM25/keyword index is consistent with HEAD (semantic may still be filling). */
    fun keywordReady(): Boolean = keywordReadyFlag.get()

    fun embeddingStatus(): EmbeddingStatus {
        val state = embeddingState.get()
        val done = embeddingDone.get()
        val total = embeddingTotal.get()
        // Rate + ETA only while a pass is actively running and has made measurable progress.
        var rate: Double? = null
        var eta: Long? = null
        if (state == EmbeddingState.RUNNING) {
            val elapsedSec = (System.currentTimeMillis() - passStartMs) / 1000.0
            val progressed = done - passStartDone
            if (elapsedSec > 0.0 && progressed > 0) {
                rate = progressed / elapsedSec
                val remaining = (total - done).coerceAtLeast(0)
                eta = Math.round(remaining / rate!!)
            }
        }
        return EmbeddingStatus(state, done, total, embedder.model, embeddingError, rate, eta)
    }

    /** Open the index and bring it consistent with HEAD. Non-blocking unless [blockStartup]. */
    fun start(): IndexService {
        if (blockStartup) {
            runBoot()
        } else {
            bootThread = Thread({ runCatching { runBoot() }.onFailure { log.error("index boot failed", it) } },
                "svod-index-boot").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.also { it.start() }
        }
        return this
    }

    /** Keyword-first catch-up, then (if an embedder is active) the throttled embedding backlog. */
    private fun runBoot() {
        val emb = embedder
        val meta = exec.submit<IndexMeta?> { IndexMeta.load(metaFile) }.get()
        // Genuine model/dim/schema change ⇒ the vector field is incompatible: wipe and rebuild.
        // knownDim() never does a network probe (a cold remote endpoint must not block/crash boot);
        // IndexMeta.compatibleWith treats an unknown (0) dimension as a wildcard, so a remote whose
        // dim isn't probed yet resumes against its existing index instead of wiping it.
        if (meta != null && !meta.compatibleWith(schemaVersion, emb.model, emb.knownDim())) {
            // This is the ONLY restart path that re-embeds the whole vault. It is expensive (re-hits a
            // remote/GPU embedder for every chunk), so name the exact reason — that line is how an
            // operator diagnoses a vault that unexpectedly re-embeds on every boot.
            log.warn(
                "full re-embed: persisted index (model={} dim={} schema={}) is incompatible with the " +
                    "configured embedder (model={} dim={} schema={}) — {}",
                meta.embeddingModel, meta.embeddingDim, meta.schemaVersion,
                emb.model, emb.knownDim(), schemaVersion, incompatibilityReason(meta, emb),
            )
            exec.submit { index.deleteAll() }.get()
        }
        keywordCatchUp()
        if (!emb.isActive || closing.get()) return
        // keywordCatchUp() has reconciled the index to HEAD reusing every still-valid vector, so the
        // remaining backlog is exactly the chunks with no vector. Empty ⇒ semantic is already current
        // for this (model, dim, HEAD): do nothing rather than re-embed. Non-empty ⇒ resume just the
        // delta. This is the resume-across-restarts guarantee, made observable.
        val head = exec.submit<String?> { reader.headCommit() }.get()
        val missing = exec.submit<List<String>> { index.pathsMissingVectors() }.get()
        if (missing.isEmpty()) {
            log.info("semantic index up-to-date at HEAD {} (model {}); nothing to embed", shortHead(head), emb.model)
            transition(EmbeddingState.IDLE)
            return
        }
        log.info("resuming embedding: {} file(s) missing vectors at HEAD {} (model {})", missing.size, shortHead(head), emb.model)
        embedPass(missing, force = false)
    }

    private fun shortHead(head: String?): String = head?.take(8) ?: "—"

    /** Human-readable reason an existing index is incompatible with the active embedder (for the boot log). */
    private fun incompatibilityReason(meta: IndexMeta, emb: Embedder): String = when {
        meta.schemaVersion != schemaVersion -> "schema changed ${meta.schemaVersion} → $schemaVersion"
        meta.embeddingModel != emb.model -> "model changed '${meta.embeddingModel}' → '${emb.model}'"
        else -> "embedding dimension changed ${meta.embeddingDim} → ${emb.knownDim()}"
    }

    /**
     * BM25 pass: index the text of every file that differs from what's indexed, reusing any vectors
     * already stored for unchanged chunks but embedding NOTHING new. Commits in batches so lexical
     * search becomes available progressively. Runs entirely on [exec] (single Lucene writer).
     */
    private fun keywordCatchUp() {
        exec.submit {
            val head = reader.headCommit()
            if (head == null) {
                saveMeta(null); keywordReadyFlag.set(true); onSynced?.invoke(null); return@submit
            }
            val desired = reader.listMarkdown(head)
            val indexed = index.pathBlobMap()
            for (gone in indexed.keys - desired.keys) index.deletePath(gone)
            var n = 0
            for ((path, blob) in desired) {
                if (closing.get()) break
                if (indexed[path] == blob) continue
                applyDocs(path, prepare(path, head, embed = false))
                if (++n % KEYWORD_COMMIT_EVERY == 0) index.commit()
            }
            index.commit()
            saveMeta(head)
            keywordReadyFlag.set(true)
            onSynced?.invoke(head)
        }.get()
    }

    /** Embed every file still missing vectors (resumable backlog). Throttled + checkpointed. */
    private fun embedBacklog() = embedPass(exec.submit<List<String>> { index.pathsMissingVectors() }.get(), force = false)

    /**
     * Re-embed [paths] (force ⇒ re-embed even chunks that already have a vector). Files are packed
     * into groups of up to [batchSize] chunks so a remote provider gets ONE /v1/embeddings POST per
     * group (few large requests beat many tiny ones — fewer serverless cold starts) — yet each file
     * is still upserted atomically (resume granularity). Embedding compute runs on a bounded,
     * low-priority pool ([maxThreads]); all git reads + Lucene writes stay on [exec]. Commits after
     * each group (resume checkpoints). Honors pause and shutdown. Progress is counted in chunks.
     */
    private fun embedPass(paths: List<String>, force: Boolean) {
        embeddingError = null
        embeddingDone.set(0)
        embeddingTotal.set(0)
        if (paths.isEmpty()) { transition(EmbeddingState.IDLE); return }
        val head = exec.submit<String?> { reader.headCommit() }.get()
        if (head == null) { transition(EmbeddingState.IDLE); return }

        // Plan every file on exec (git reads + reuse decision); drop files that vanished.
        val plans = ArrayList<EmbedPlan>()
        for (path in paths) {
            if (closing.get()) return
            val plan = exec.submit<EmbedPlan?> { planEmbed(path, head, force) }.get()
            if (plan == null) exec.submit { index.deletePath(path) }.get()
            else if (plan.toEmbed.isNotEmpty()) plans.add(plan)
        }
        embeddingTotal.set(plans.sumOf { it.toEmbed.size })
        if (plans.isEmpty()) {
            exec.submit { index.commit() }.get()
            if (!closing.get()) { saveMeta(head); onSynced?.invoke(head) }
            transition(EmbeddingState.IDLE)
            return
        }
        transition(EmbeddingState.RUNNING)

        val groups = packIntoGroups(plans, batchSize)
        val pool: ExecutorService = Executors.newFixedThreadPool(maxThreads.coerceAtLeast(1)) { r ->
            Thread(r, "svod-embed").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
        try {
            val futures = groups.map { group ->
                pool.submit {
                    if (closing.get()) return@submit
                    awaitIfPaused()
                    if (closing.get()) return@submit
                    // One batched embed call for the whole group (the slow part, off exec). On a batch
                    // failure, retry items individually so one bad chunk (e.g. an oversized input the
                    // model rejects) is skipped+logged, not fatal — only a wholesale failure aborts.
                    val items = group.flatMap { plan -> plan.toEmbed.map { plan to it } }
                    val vecs = embedGroupResilient(items.map { embedText(it.second) })
                    // …then upsert each file (Lucene writes on exec) with whatever vectors succeeded.
                    for (plan in group) {
                        val fresh = HashMap<String, FloatArray>()
                        items.forEachIndexed { i, (p, c) -> if (p === plan) vecs[i]?.let { fresh[c.contentHash] = it } }
                        exec.submit { finishEmbed(plan.path, plan, fresh) }.get()
                    }
                    exec.submit { index.commit() }.get()
                    embeddingDone.addAndGet(items.size)
                    emitProgressThrottled()
                }
            }
            for (f in futures) { if (closing.get()) break; f.get() }
            exec.submit { index.commit() }.get()
            if (!closing.get()) {
                saveMeta(head)
                onSynced?.invoke(head)
            }
            transition(EmbeddingState.IDLE)
        } catch (t: Throwable) {
            embeddingError = (t.cause ?: t).message ?: t.toString()
            runCatching { exec.submit { index.commit() }.get() }
            transition(EmbeddingState.ERROR)
            log.error("embedding pass failed", t)
        } finally {
            pool.shutdownNow()
            suppressSemantic = false
        }
    }

    /** Greedily pack file plans into groups whose total to-embed chunk count stays within [cap]. */
    private fun packIntoGroups(plans: List<EmbedPlan>, cap: Int): List<List<EmbedPlan>> {
        val groups = ArrayList<List<EmbedPlan>>()
        var cur = ArrayList<EmbedPlan>()
        var curSize = 0
        for (p in plans) {
            val n = p.toEmbed.size
            if (curSize > 0 && curSize + n > cap) { groups.add(cur); cur = ArrayList(); curSize = 0 }
            cur.add(p); curSize += n
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups
    }

    private fun awaitIfPaused() {
        pauseLock.withLock {
            while (paused && !closing.get()) {
                transition(EmbeddingState.PAUSED)
                pauseCond.await(1, TimeUnit.SECONDS)
            }
            if (!closing.get() && embeddingState.get() == EmbeddingState.PAUSED) transition(EmbeddingState.RUNNING)
        }
    }

    /** Pause the background embedding pass (idempotent). Keyword search is unaffected. */
    fun pause() {
        paused = true
        if (embeddingState.get() == EmbeddingState.RUNNING) transition(EmbeddingState.PAUSED)
    }

    /** Resume a paused embedding pass (idempotent). */
    fun resume() {
        pauseLock.withLock { paused = false; pauseCond.signalAll() }
    }

    /** Force a full background re-embed with the current embedder (e.g. POST /index/reembed). */
    fun reembed() {
        if (!embedder.isActive) return
        launchBackground { embedPass(reader.headCommit()?.let { reader.listMarkdown(it).keys.toList() } ?: emptyList(), force = true) }
    }

    /**
     * Swap the active embedder and rebuild vectors in the background (provider/model change).
     * A dimension change wipes the vector index (Lucene cannot mix dimensions); keyword search
     * keeps working and semantic is suppressed until the new vectors are fully built.
     */
    fun setEmbedder(newEmbedder: Embedder) {
        val prevDim = indexedDim() ?: 0
        val previous = embedder
        embedder = newEmbedder
        if (previous !== newEmbedder) (previous as? AutoCloseable)?.let { runCatching { it.close() } }
        // Fresh swap ⇒ a fresh run: clear any inherited pause/error so the new embedder starts clean.
        resume()
        embeddingError = null
        embeddingDone.set(0)
        embeddingTotal.set(0)
        transition(EmbeddingState.RUNNING)
        suppressSemantic = newEmbedder.isActive
        launchBackground {
            if (!newEmbedder.isActive) {
                // Switched to BM25-only: drop vectors so semantic returns nothing, keep the text.
                exec.submit { dropAllVectors() }.get()
                saveMetaCurrent()
                transition(EmbeddingState.IDLE)
                suppressSemantic = false
                return@launchBackground
            }
            // knownDim() never probes (a cold remote must not block the swap). If the new dim is
            // unknown (0, remote not yet probed) or differs, we MUST wipe — the vec field is fixed
            // width and Lucene cannot mix dimensions. Only an in-place re-embed is safe when the new
            // dimension is known AND equal to what's indexed.
            val newDim = newEmbedder.knownDim()
            if (newDim == 0 || newDim != prevDim) {
                exec.submit { index.deleteAll() }.get()
                keywordReadyFlag.set(false)
                keywordCatchUp()
                embedBacklog()
            } else {
                // Same dimension, different model: keep text + keyword, re-embed all chunks.
                embedPass(reader.headCommit()?.let { reader.listMarkdown(it).keys.toList() } ?: emptyList(), force = true)
            }
        }
    }

    private fun launchBackground(job: () -> Unit) {
        Thread({ runCatching { job() }.onFailure { embeddingError = it.message; transition(EmbeddingState.ERROR) } },
            "svod-index-rebuild").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Re-index text for every file (no vectors) under HEAD; used when switching to BM25-only. */
    private fun dropAllVectors() {
        val head = reader.headCommit() ?: return
        for (path in reader.listMarkdown(head).keys) applyDocs(path, prepare(path, head, embed = false))
        index.commit()
    }

    private fun transition(state: EmbeddingState) {
        // Start (or restart, e.g. after a pause) the throughput clock when the pass enters RUNNING.
        if (state == EmbeddingState.RUNNING && embeddingState.get() != EmbeddingState.RUNNING) {
            passStartMs = System.currentTimeMillis()
            passStartDone = embeddingDone.get()
        }
        embeddingState.set(state)
        onProgress?.invoke(embeddingDone.get(), embeddingTotal.get(), state.name.lowercase())
    }

    private fun emitProgressThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastProgressEmit < PROGRESS_THROTTLE_MS) return
        lastProgressEmit = now
        onProgress?.invoke(embeddingDone.get(), embeddingTotal.get(), embeddingState.get().name.lowercase())
    }

    /** Non-blocking: enqueue an incremental sync. Called by the engine after a commit. */
    fun onCommit(@Suppress("UNUSED_PARAMETER") commit: String) {
        exec.submit { runCatching { sync() }.onFailure { System.err.println("indexer sync failed: $it") } }
    }

    /** Block until every queued index task has drained (test/diagnostic helper). */
    fun waitIdle() = submitBlocking { }

    /** Force a full HEAD reconcile (self-heal). Blocks. */
    fun reconcileNow() = submitBlocking { reconcile() }

    fun headCommitIndexed(): String? = IndexMeta.load(metaFile)?.headCommit

    fun indexedModel(): String? = IndexMeta.load(metaFile)?.embeddingModel

    fun indexedDim(): Int? = IndexMeta.load(metaFile)?.embeddingDim

    /** The active reranker for the read-only settings view. */
    fun rerankerInfo(): RerankerInfo = reranker.let { RerankerInfo(it.provider, it.model, it.isActive) }

    data class RerankerInfo(val provider: String, val model: String, val active: Boolean)

    fun docCount(): Int = index.numDocs()

    // ---- search (thread-safe; no executor needed) ----

    /**
     * Path A enumeration: every distinct note path matching [filters] (type/tag/prefix + the default
     * lifecycle hiding), unranked, deterministic by path, capped at [limit]. The caller reads each
     * note's full content. This is the "load the rule book in full" path, not relevance ranking.
     */
    fun enumerate(filters: SearchFilters, limit: Int = 500): List<String> =
        index.enumeratePaths(index.buildFilter(filters), limit)

    fun search(q: SearchQuery): SearchResult {
        val start = System.nanoTime()
        // Blank text AND no user-facing filter ⇒ nothing to search (the lifecycle defaults alone must
        // not turn an empty query into a match-all browse). The App API already 400s this case.
        if (q.text.isBlank() && q.filters.isEmpty) return SearchResult(emptyList(), q.mode, 0)
        val filter = index.buildFilter(q.filters)
        val cand = maxOf(q.limit * 5, 50)

        // A filter-only query (blank text, e.g. "browse by tag") matches every doc passing the filter,
        // via the lexical leg only — there's no query text to embed, so semantic is skipped.
        val blankQuery = q.text.isBlank()
        // Semantic is opt-in over BM25: inactive embedder (or a rebuild in flight, or a filter-only
        // query) ⇒ lexical only.
        val active = embedder.isActive && !suppressSemantic && !blankQuery
        val wantKeyword = blankQuery || q.mode != SearchMode.SEMANTIC || !active
        val wantSemantic = !blankQuery && q.mode != SearchMode.KEYWORD && active
        val keyword = if (wantKeyword) keywordLeg(q.text, filter, cand) else emptyList()
        val semantic = if (wantSemantic) semanticLeg(q.text, filter, cand) else emptyList()
        val kwIds = keyword.map { it.first }
        val semIds = semantic.map { it.first }
        val kwSet = kwIds.toHashSet()
        val semSet = semIds.toHashSet()

        val ordered: List<Pair<String, Double>> = when {
            !active || q.mode == SearchMode.KEYWORD -> keyword.map { it.first to it.second.toDouble() }
            q.mode == SearchMode.SEMANTIC -> semantic.map { it.first to it.second.toDouble() }
            else -> Rrf.fuse(listOf(kwIds, semIds)).entries.map { it.key to it.value }
        }

        val ranked = maybeRerank(q.text, ordered)
        val hits = ranked.asSequence()
            .mapNotNull { (id, score) ->
                index.loadChunk(id)?.let { c ->
                    SearchHit(
                        chunkId = id,
                        path = c.path,
                        heading = c.heading,
                        snippet = snippet(c.text, q.text),
                        tags = c.tags,
                        score = score,
                        matchedKeyword = id in kwSet,
                        matchedSemantic = id in semSet,
                    )
                }
            }
            .take(q.limit)
            .toList()

        return SearchResult(hits, q.mode, (System.nanoTime() - start) / 1_000_000)
    }

    private fun keywordLeg(text: String, filter: Query?, k: Int): List<Pair<String, Float>> {
        val userQuery = parseUserQuery(text)
        // No query text but a filter present ⇒ filter-only browse: every doc passing the filter.
        // No query text and no filter ⇒ nothing to search.
        if (userQuery == null && filter == null) return emptyList()
        val b = BooleanQuery.Builder().add(userQuery ?: MatchAllDocsQuery(), BooleanClause.Occur.MUST)
        if (filter != null) b.add(filter, BooleanClause.Occur.FILTER)
        return index.keywordSearch(b.build(), k)
    }

    private fun semanticLeg(text: String, filter: Query?, k: Int): List<Pair<String, Float>> {
        if (text.isBlank()) return emptyList()
        // A remote query-embed can fail (endpoint cold/down) — degrade to keyword rather than error
        // the whole search. The background pass surfaces the failure via embeddingStatus().
        return try {
            index.semanticSearch(embedder.embedQuery(text), k, filter)
        } catch (e: Exception) {
            System.err.println("semantic query-embed failed, falling back to keyword: ${e.message}")
            emptyList()
        }
    }

    /**
     * Second-stage rerank of the top [rerankTopK] fused candidates with a cross-encoder, when a
     * reranker is active. Re-scored items lead (best-first by rerank score); candidates beyond the
     * cap keep their fused order behind them. ANY failure (cold/down endpoint, vanished chunk)
     * degrades to the fused order — reranking never errors a search.
     */
    private fun maybeRerank(query: String, ordered: List<Pair<String, Double>>): List<Pair<String, Double>> {
        val rr = reranker
        if (!rr.isActive || query.isBlank() || ordered.size <= 1) return ordered
        val k = rerankTopK.coerceAtMost(ordered.size)
        val head = ordered.subList(0, k)
        val tail = ordered.subList(k, ordered.size)
        val texts = head.map { index.loadChunk(it.first)?.let { c -> if (c.heading.isBlank()) c.text else "${c.heading}\n${c.text}" } }
        if (texts.any { it == null }) return ordered // a candidate vanished mid-flight; don't rerank a partial set
        return try {
            val scores = rr.rerank(query, texts.filterNotNull())
            val rescored = head.indices.sortedByDescending { scores[it] }.map { head[it].first to scores[it].toDouble() }
            rescored + tail
        } catch (e: Exception) {
            log.warn("rerank failed, falling back to fused order: {}", e.message)
            ordered
        }
    }

    /** Supports fuzzy (`term~`), prefix (`term*`), phrase (`"..."`) and field-scoped queries. */
    private fun parseUserQuery(text: String): Query? {
        if (text.isBlank()) return null
        // A lone "*" means "match everything" (a deterministic match-all so a filter ANDs cleanly),
        // not a literal wildcard term — the latter parses inconsistently across analyzers.
        if (text.trim() == "*") return MatchAllDocsQuery()
        val parser = MultiFieldQueryParser(arrayOf("text", "heading"), index.analyzer()).apply {
            defaultOperator = QueryParser.Operator.OR
        }
        return try {
            parser.parse(text)
        } catch (_: Exception) {
            // Fall back to a literal search if the user typed something the parser rejects.
            try { parser.parse(QueryParser.escape(text)) } catch (_: Exception) { TermQuery(Term("text", text.lowercase())) }
        }
    }

    private fun snippet(text: String, query: String, window: Int = 160): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return ""
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 1 }
        val lower = flat.lowercase()
        val at = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        if (at < 0) return flat.take(window) + if (flat.length > window) "…" else ""
        val begin = (at - window / 3).coerceAtLeast(0)
        val end = (begin + window).coerceAtMost(flat.length)
        val core = flat.substring(begin, end)
        var snip = core
        for (t in terms.toSortedSet(compareByDescending { it.length })) {
            snip = Regex("(?i)" + Regex.escape(t)).replace(snip) { "**${it.value}**" }
        }
        return (if (begin > 0) "…" else "") + snip + (if (end < flat.length) "…" else "")
    }

    // ---- reconciliation (all on the indexer thread) ----

    private fun reconcile() {
        val head = reader.headCommit()
        if (head == null) {
            saveMeta(null)
            return
        }
        val desired = reader.listMarkdown(head)
        val indexed = index.pathBlobMap()
        for (gone in indexed.keys - desired.keys) index.deletePath(gone)
        for ((path, blob) in desired) if (indexed[path] != blob) applyDocs(path, prepare(path, head, embed = true))
        index.commit()
        saveMeta(head)
        onSynced?.invoke(head)
    }

    private fun sync() {
        val head = reader.headCommit() ?: return saveMeta(null)
        val prev = IndexMeta.load(metaFile)?.headCommit
        if (prev == head) return
        if (prev == null) return reconcile()

        val changes = try {
            reader.diffMarkdown(prev, head)
        } catch (_: Exception) {
            // prev commit no longer resolvable (history rewrite/gc) → fall back to self-heal
            return reconcile()
        }
        for (c in changes) {
            when (c.type) {
                DiffEntry.ChangeType.DELETE -> index.deletePath(c.path)
                DiffEntry.ChangeType.RENAME -> {
                    index.deletePath(c.path)
                    applyDocs(c.newPath, prepare(c.newPath, head, embed = true))
                }
                else -> {
                    val p = if (c.type == DiffEntry.ChangeType.COPY) c.newPath else c.path
                    applyDocs(p, prepare(p, head, embed = true))
                }
            }
        }
        index.commit()
        saveMeta(head)
        onSynced?.invoke(head)
    }

    /** Prepared documents for one file: blob id, tags/created, memory meta, and resolved chunk docs. */
    private class FileDocs(val blob: String, val tags: List<String>, val created: Long?, val memory: LuceneIndex.MemoryMeta, val docs: List<LuceneIndex.ChunkDoc>)

    private fun memoryMetaOf(doc: ParsedDoc) = LuceneIndex.MemoryMeta(doc.type, doc.status, doc.supersededBy, doc.expiresAt)

    /**
     * Build the Lucene docs for [path] at [commit]. [embed]=false ⇒ text + reused vectors only (the
     * keyword-first pass embeds nothing new). [embed]=true ⇒ embed chunks not already vectored
     * ([force] re-embeds all). Returns null when the file is gone/empty (caller deletes the path).
     * Git reads + embedding are pure (no Lucene writes) so this is safe to run off [exec].
     */
    private fun prepare(path: String, commit: String, embed: Boolean, force: Boolean = false): FileDocs? {
        val bytes = reader.readBlob(commit, path) ?: return null
        val blob = reader.blobId(commit, path) ?: return null
        val doc = MarkdownChunker.parse(String(bytes, Charsets.UTF_8))
        if (doc.chunks.isEmpty()) return null
        val emb = embedder

        val chunkDocs = if (!emb.isActive) {
            doc.chunks.map { LuceneIndex.ChunkDoc(it, null) }
        } else {
            val reusable = if (force) emptyMap() else index.existingVectors(path)
            if (!embed) {
                doc.chunks.map { LuceneIndex.ChunkDoc(it, reusable[it.contentHash]) }
            } else {
                val toEmbed = doc.chunks.filter { it.contentHash !in reusable }
                val fresh: Map<String, FloatArray> = if (toEmbed.isEmpty()) emptyMap()
                else embedInBatches(toEmbed.map { embedText(it) })
                    .let { vecs -> toEmbed.mapIndexed { i, c -> c.contentHash to vecs[i] }.toMap() }
                doc.chunks.map { c -> LuceneIndex.ChunkDoc(c, reusable[c.contentHash] ?: fresh.getValue(c.contentHash)) }
            }
        }
        return FileDocs(blob, doc.tags, doc.created, memoryMetaOf(doc), chunkDocs)
    }

    /** Apply prepared docs to Lucene (MUST run on [exec]). Null prep ⇒ delete the path. */
    private fun applyDocs(path: String, prep: FileDocs?) {
        if (prep == null) { index.deletePath(path); return }
        index.upsertFile(path, prep.blob, prep.tags, prep.created, prep.docs, prep.memory)
    }

    /** A file's chunks split into reusable (already-vectored) and to-embed, for the background pass. */
    private class EmbedPlan(
        val path: String,
        val blob: String,
        val tags: List<String>,
        val created: Long?,
        val memory: LuceneIndex.MemoryMeta,
        val chunks: List<Chunk>,
        val reusable: Map<String, FloatArray>,
        val toEmbed: List<Chunk>,
    )

    /** Read git + decide what needs embedding (MUST run on [exec]; pure reads, no embedding). */
    private fun planEmbed(path: String, commit: String, force: Boolean): EmbedPlan? {
        val bytes = reader.readBlob(commit, path) ?: return null
        val blob = reader.blobId(commit, path) ?: return null
        val doc = MarkdownChunker.parse(String(bytes, Charsets.UTF_8))
        if (doc.chunks.isEmpty()) return null
        val reusable = if (force) emptyMap() else index.existingVectors(path)
        val toEmbed = doc.chunks.filter { it.contentHash !in reusable }
        return EmbedPlan(path, blob, doc.tags, doc.created, memoryMetaOf(doc), doc.chunks, reusable, toEmbed)
    }

    /** Combine reused + freshly-embedded vectors and upsert (MUST run on [exec]). */
    private fun finishEmbed(path: String, plan: EmbedPlan, fresh: Map<String, FloatArray>) {
        val docs = plan.chunks.map { c ->
            LuceneIndex.ChunkDoc(c, plan.reusable[c.contentHash] ?: fresh[c.contentHash])
        }
        index.upsertFile(path, plan.blob, plan.tags, plan.created, docs, plan.memory)
    }

    /**
     * Embed [texts] as one batch; if that fails, retry each text individually so a single bad chunk
     * (e.g. one the model rejects as too long) is skipped (null in the result) and logged instead of
     * aborting the whole pass. Throws only when NOTHING embeds — that looks like a real outage (cold/
     * down endpoint), which must still surface as ERROR rather than silently produce no vectors.
     */
    private fun embedGroupResilient(texts: List<String>): List<FloatArray?> {
        if (texts.isEmpty()) return emptyList()
        return try {
            embedInBatches(texts)
        } catch (batchErr: Exception) {
            val out = arrayOfNulls<FloatArray>(texts.size)
            var ok = 0
            for (i in texts.indices) {
                try {
                    out[i] = embedder.embedPassages(listOf(texts[i])).firstOrNull()
                    if (out[i] != null) ok++
                } catch (e: Exception) {
                    log.warn("skipping a chunk that failed to embed: {}", (e.cause ?: e).message)
                }
            }
            if (ok == 0) throw batchErr // nothing embedded ⇒ treat as an outage, abort to ERROR
            out.toList()
        }
    }

    /** Embed [texts] in windows of [batchSize] so a provider is never handed an unbounded batch. */
    private fun embedInBatches(texts: List<String>): List<FloatArray> {
        if (texts.size <= batchSize) return embedder.embedPassages(texts)
        val out = ArrayList<FloatArray>(texts.size)
        var i = 0
        while (i < texts.size) {
            out += embedder.embedPassages(texts.subList(i, minOf(i + batchSize, texts.size)))
            i += batchSize
        }
        return out
    }

    private fun embedText(c: Chunk): String = if (c.heading.isEmpty()) c.text else "${c.heading}\n${c.text}"

    private fun saveMeta(head: String?) {
        Files.createDirectories(indexDir)
        // knownDim() (not dim) so the keyword-first pass never triggers a remote probe; it is 0 until
        // the first successful embed, then the post-embed saveMeta records the real dimension.
        IndexMeta.save(metaFile, IndexMeta(schemaVersion, embedder.model, embedder.knownDim(), head))
    }

    private fun saveMetaCurrent() = saveMeta(IndexMeta.load(metaFile)?.headCommit)

    private fun <T> submitBlocking(task: () -> T): T = exec.submit(task).get()

    override fun close() {
        closing.set(true)
        resume() // unblock any paused embedding workers so they can observe `closing` and exit
        runCatching { bootThread?.join(2000) }
        exec.shutdown()
        if (!exec.awaitTermination(30, TimeUnit.SECONDS)) exec.shutdownNow()
        index.close()
        reader.close()
    }

    private companion object {
        const val KEYWORD_COMMIT_EVERY = 200
        const val PROGRESS_THROTTLE_MS = 400L
    }
}
