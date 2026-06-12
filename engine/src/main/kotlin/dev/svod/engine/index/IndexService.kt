package dev.svod.engine.index

import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.eclipse.jgit.diff.DiffEntry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the index and keeps it an exact function of git HEAD.
 *
 * All index mutations run on a single dedicated thread, entirely OFF the engine's write
 * path: the engine calls [onCommit] after each commit, which only enqueues a sync and
 * returns immediately — writes never wait on embedding or Lucene I/O. Reads (search) are
 * thread-safe and need no coordination.
 *
 * Reconciliation triggers:
 *  - construction with an incompatible model/dim/schema ⇒ full reindex (migration)
 *  - [onCommit] / [sync] ⇒ incremental diff from the last indexed commit
 *  - [reconcile] ⇒ full HEAD compare (self-heal after drift or an index wipe)
 */
class IndexService(
    private val root: Path,
    private val indexDir: Path,
    private val embedder: Embedder,
    private val schemaVersion: Int = IndexMeta.SCHEMA_VERSION,
) : AutoCloseable {

    private val index = LuceneIndex(indexDir, embedder.dim)
    private val reader = GitReader(root)
    private val metaFile = indexDir.resolve("meta.json")
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "svod-indexer").apply { isDaemon = true } }

    /** Open the index and bring it consistent with HEAD (migrating if the model changed). */
    fun start(): IndexService {
        submitBlocking {
            val meta = IndexMeta.load(metaFile)
            if (meta == null || !meta.compatibleWith(schemaVersion, embedder.model, embedder.dim)) {
                fullReindex()
            } else {
                sync()
            }
        }
        return this
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

    fun docCount(): Int = index.numDocs()

    // ---- search (thread-safe; no executor needed) ----

    fun search(q: SearchQuery): SearchResult {
        val start = System.nanoTime()
        val filter = index.buildFilter(q.filters)
        val cand = maxOf(q.limit * 5, 50)

        val keyword = if (q.mode != SearchMode.SEMANTIC) keywordLeg(q.text, filter, cand) else emptyList()
        val semantic = if (q.mode != SearchMode.KEYWORD) semanticLeg(q.text, filter, cand) else emptyList()
        val kwIds = keyword.map { it.first }
        val semIds = semantic.map { it.first }
        val kwSet = kwIds.toHashSet()
        val semSet = semIds.toHashSet()

        val ordered: List<Pair<String, Double>> = when (q.mode) {
            SearchMode.HYBRID -> Rrf.fuse(listOf(kwIds, semIds)).entries.map { it.key to it.value }
            SearchMode.KEYWORD -> keyword.map { it.first to it.second.toDouble() }
            SearchMode.SEMANTIC -> semantic.map { it.first to it.second.toDouble() }
        }

        val hits = ordered.asSequence()
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
        val userQuery = parseUserQuery(text) ?: return emptyList()
        val b = BooleanQuery.Builder().add(userQuery, BooleanClause.Occur.MUST)
        if (filter != null) b.add(filter, BooleanClause.Occur.FILTER)
        return index.keywordSearch(b.build(), k)
    }

    private fun semanticLeg(text: String, filter: Query?, k: Int): List<Pair<String, Float>> {
        if (text.isBlank()) return emptyList()
        return index.semanticSearch(embedder.embedQuery(text), k, filter)
    }

    /** Supports fuzzy (`term~`), prefix (`term*`), phrase (`"..."`) and field-scoped queries. */
    private fun parseUserQuery(text: String): Query? {
        if (text.isBlank()) return null
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

    private fun fullReindex() {
        index.deleteAll()
        reconcile()
    }

    private fun reconcile() {
        val head = reader.headCommit()
        if (head == null) {
            saveMeta(null)
            return
        }
        val desired = reader.listMarkdown(head)
        val indexed = index.pathBlobMap()
        for (gone in indexed.keys - desired.keys) index.deletePath(gone)
        for ((path, blob) in desired) if (indexed[path] != blob) indexFile(path, head)
        index.commit()
        saveMeta(head)
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
                    indexFile(c.newPath, head)
                }
                else -> indexFile(if (c.type == DiffEntry.ChangeType.COPY) c.newPath else c.path, head)
            }
        }
        index.commit()
        saveMeta(head)
    }

    /**
     * Index one file at [commit], reusing embeddings for chunks whose content is unchanged
     * (so only changed chunks are sent to the embedder).
     */
    private fun indexFile(path: String, commit: String) {
        val bytes = reader.readBlob(commit, path) ?: run { index.deletePath(path); return }
        val blob = reader.blobId(commit, path) ?: return
        val doc = MarkdownChunker.parse(String(bytes, Charsets.UTF_8))
        if (doc.chunks.isEmpty()) { index.deletePath(path); return }

        val reusable = index.existingVectors(path)
        val toEmbed = doc.chunks.filter { it.contentHash !in reusable }
        val freshVectors: Map<String, FloatArray> = if (toEmbed.isEmpty()) {
            emptyMap()
        } else {
            val vecs = embedder.embedPassages(toEmbed.map { embedText(it) })
            toEmbed.mapIndexed { i, c -> c.contentHash to vecs[i] }.toMap()
        }

        val chunkDocs = doc.chunks.map { c ->
            LuceneIndex.ChunkDoc(c, reusable[c.contentHash] ?: freshVectors.getValue(c.contentHash))
        }
        index.upsertFile(path, blob, doc.tags, doc.created, chunkDocs)
    }

    private fun embedText(c: Chunk): String = if (c.heading.isEmpty()) c.text else "${c.heading}\n${c.text}"

    private fun saveMeta(head: String?) {
        Files.createDirectories(indexDir)
        IndexMeta.save(metaFile, IndexMeta(schemaVersion, embedder.model, embedder.dim, head))
    }

    private fun <T> submitBlocking(task: () -> T): T = exec.submit(task).get()

    override fun close() {
        exec.shutdown()
        if (!exec.awaitTermination(30, TimeUnit.SECONDS)) exec.shutdownNow()
        index.close()
        reader.close()
    }
}
