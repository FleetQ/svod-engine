package dev.svod.engine.index

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.LongPoint
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.FieldExistsQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.BytesRef
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * Lucene-backed chunk index: BM25 over text always, plus HNSW kNN over embeddings when an
 * embedder is active. The embedding bytes are stored alongside each chunk so incremental
 * reindexing can reuse the vector of an unchanged section instead of re-embedding it.
 *
 * Vectors are optional: with the `none` embedder, documents carry no `vec` field and the
 * index is a pure BM25 store. Switching providers changes the recorded model, which forces
 * a full reindex, so segments are never mixed across vector dimensions.
 *
 * Writes are expected to come from a single thread (the [IndexService]); reads are
 * thread-safe via [SearcherManager].
 */
class LuceneIndex(private val dir: Path) : AutoCloseable {

    private val analyzer = StandardAnalyzer()
    private val directory = FSDirectory.open(dir)
    private val writer = IndexWriter(directory, IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))
    private val searcherManager = SearcherManager(writer, null)

    /** A chunk plus its embedding (null in BM25-only mode), ready to be written. */
    data class ChunkDoc(val chunk: Chunk, val vector: FloatArray?)

    fun numDocs(): Int = withSearcher { it.indexReader.numDocs() }

    /** path → file blob id for every indexed chunk (collapsed to one per path). */
    fun pathBlobMap(): Map<String, String> = withSearcher { s ->
        val n = s.indexReader.numDocs()
        if (n == 0) return@withSearcher emptyMap()
        val out = HashMap<String, String>()
        val td = s.search(MatchAllDocsQuery(), n)
        val sf = s.storedFields()
        for (sd in td.scoreDocs) {
            val d = sf.document(sd.doc)
            out[d.get("path")] = d.get("blob")
        }
        out
    }

    /**
     * Paths that have at least one indexed chunk WITHOUT a stored vector — the embedding backlog.
     * Used by the background indexer to find (and resume) work after a keyword-first pass: every
     * such path still needs embedding. Empty when the index is fully embedded (or BM25-only).
     *
     * The backlog is found by NEGATING [FieldExistsQuery] over the kNN field rather than scanning
     * every document: stored fields are then decompressed only for chunks that actually lack a
     * vector — none, once embedding has caught up — instead of all of them. This runs twice on the
     * boot path, so on a large vault the difference is felt at startup.
     *
     * `vec` and `vecBytes` are written together in [upsertFile] (one `if (vector != null)` branch)
     * and a schema change forces a full reindex, so testing the kNN field is equivalent to testing
     * the stored copy.
     */
    fun pathsMissingVectors(): List<String> = withSearcher { s ->
        val n = s.indexReader.numDocs()
        if (n == 0) return@withSearcher emptyList()
        val q = BooleanQuery.Builder()
            .add(MatchAllDocsQuery(), BooleanClause.Occur.MUST)
            .add(FieldExistsQuery(VEC_FIELD), BooleanClause.Occur.MUST_NOT)
            .build()
        val td = s.search(q, n)
        val sf = s.storedFields()
        val missing = LinkedHashSet<String>()
        for (sd in td.scoreDocs) missing.add(sf.document(sd.doc).get("path"))
        missing.toList()
    }

    /** contentHash → embedding for the chunks currently indexed under [path] (reuse source). */
    fun existingVectors(path: String): Map<String, FloatArray> = withSearcher { s ->
        if (s.indexReader.numDocs() == 0) return@withSearcher emptyMap()
        val out = HashMap<String, FloatArray>()
        val td = s.search(TermQuery(Term("path", path)), Int.MAX_VALUE)
        val sf = s.storedFields()
        for (sd in td.scoreDocs) {
            val d = sf.document(sd.doc)
            val hash = d.get("contentHash") ?: continue
            val bytes = d.getBinaryValue(VEC_BYTES_FIELD) ?: continue
            out[hash] = bytesToFloats(bytes)
        }
        out
    }

    /** Memory typing/lifecycle fields parsed from frontmatter, indexed for filtering. */
    data class MemoryMeta(val type: String? = null, val status: String? = null, val supersededBy: String? = null, val expiresAt: Long? = null)

    /** Replace all chunks for [path] with [docs]. Caller has already resolved vectors. */
    fun upsertFile(path: String, blob: String, tags: List<String>, created: Long?, docs: List<ChunkDoc>, memory: MemoryMeta = MemoryMeta()) {
        writer.deleteDocuments(Term("path", path))
        for (cd in docs) {
            val d = Document()
            d.add(StringField("chunkId", "$path#${cd.chunk.ordinal}", Field.Store.YES))
            d.add(StringField("path", path, Field.Store.YES))
            d.add(StringField("blob", blob, Field.Store.YES))
            d.add(StringField("contentHash", cd.chunk.contentHash, Field.Store.YES))
            d.add(TextField("heading", cd.chunk.heading, Field.Store.YES))
            d.add(TextField("text", cd.chunk.text, Field.Store.YES))
            d.add(StoredField("ord", cd.chunk.ordinal))
            for (t in tags) d.add(StringField("tag", t, Field.Store.YES))
            if (created != null) d.add(LongPoint("created", created))
            // Memory typing/lifecycle (only indexed when present → notes without these stay unaffected).
            memory.type?.let { d.add(StringField("type", it, Field.Store.YES)) }
            memory.status?.let { d.add(StringField("status", it, Field.Store.YES)) }
            if (memory.supersededBy != null) {
                d.add(StringField("superseded", "true", Field.Store.NO))
                d.add(StoredField("supersededBy", memory.supersededBy))
            }
            memory.expiresAt?.let { d.add(LongPoint("expiresAt", it)) }
            if (cd.vector != null) {
                d.add(KnnFloatVectorField(VEC_FIELD, cd.vector, VectorSimilarityFunction.COSINE))
                d.add(StoredField(VEC_BYTES_FIELD, floatsToBytes(cd.vector)))
            }
            writer.addDocument(d)
        }
    }

    fun deletePath(path: String) {
        writer.deleteDocuments(Term("path", path))
    }

    fun commit() {
        writer.commit()
        searcherManager.maybeRefreshBlocking()
    }

    /** Drop every document (used when migrating to a new embedding model). */
    fun deleteAll() {
        writer.deleteAll()
        commit()
    }

    fun keywordSearch(query: Query, k: Int): List<Pair<String, Float>> = withSearcher { s ->
        if (s.indexReader.numDocs() == 0) return@withSearcher emptyList()
        val td = s.search(query, k)
        val sf = s.storedFields()
        td.scoreDocs.map { sf.document(it.doc).get("chunkId") to it.score }
    }

    fun semanticSearch(vector: FloatArray, k: Int, filter: Query?): List<Pair<String, Float>> = withSearcher { s ->
        if (s.indexReader.numDocs() == 0) return@withSearcher emptyList()
        val q = KnnFloatVectorQuery(VEC_FIELD, vector, k, filter)
        val td = s.search(q, k)
        val sf = s.storedFields()
        td.scoreDocs.map { sf.document(it.doc).get("chunkId") to it.score }
    }

    /** Stored fields for a chunk id, for building a result row. */
    fun loadChunk(chunkId: String): StoredChunk? = withSearcher { s ->
        val td = s.search(TermQuery(Term("chunkId", chunkId)), 1)
        val sd = td.scoreDocs.firstOrNull() ?: return@withSearcher null
        val d = s.storedFields().document(sd.doc)
        StoredChunk(
            chunkId = chunkId,
            path = d.get("path"),
            heading = d.get("heading") ?: "",
            text = d.get("text") ?: "",
            tags = d.getValues("tag").toList(),
        )
    }

    data class StoredChunk(
        val chunkId: String,
        val path: String,
        val heading: String,
        val text: String,
        val tags: List<String>,
    )

    /**
     * Build a Lucene filter from structured search filters (applied to both legs). Beyond the
     * user-facing filters it ALWAYS applies the default memory-lifecycle hiding (unless
     * [SearchFilters.includeAll]): revoked / provisional / superseded / expired memories are
     * excluded from recall. Notes that don't carry these frontmatter fields never match an
     * exclusion, so ordinary notes are unaffected. [nowEpoch] is the expiry cutoff (now).
     */
    fun buildFilter(filters: SearchFilters, includeMessy: Boolean = false, nowEpoch: Long = System.currentTimeMillis() / 1000): Query? {
        val b = BooleanQuery.Builder()
        var positives = 0
        for (tag in filters.tags) { b.add(TermQuery(Term("tag", tag)), BooleanClause.Occur.FILTER); positives++ }
        filters.pathPrefix?.let { b.add(PrefixQuery(Term("path", it)), BooleanClause.Occur.FILTER); positives++ }
        filters.type?.let { b.add(TermQuery(Term("type", it.lowercase())), BooleanClause.Occur.FILTER); positives++ }
        if (filters.createdFrom != null || filters.createdTo != null) {
            b.add(LongPoint.newRangeQuery("created", filters.createdFrom ?: Long.MIN_VALUE, filters.createdTo ?: Long.MAX_VALUE), BooleanClause.Occur.FILTER); positives++
        }
        var negatives = 0
        if (filters.status != null) {
            // Explicit status request → positive filter; the default status-hiding is skipped for it.
            b.add(TermQuery(Term("status", filters.status.lowercase())), BooleanClause.Occur.FILTER); positives++
        } else if (!filters.includeAll) {
            b.add(TermQuery(Term("status", "revoked")), BooleanClause.Occur.MUST_NOT); negatives++
            b.add(TermQuery(Term("status", "provisional")), BooleanClause.Occur.MUST_NOT); negatives++
        }
        if (!filters.includeAll) {
            b.add(TermQuery(Term("superseded", "true")), BooleanClause.Occur.MUST_NOT); negatives++
            b.add(LongPoint.newRangeQuery("expiresAt", Long.MIN_VALUE, nowEpoch), BooleanClause.Occur.MUST_NOT); negatives++
        }
        // `messy/` quarantine: drafts are hidden from default recall unless includeAll, the
        // `includeMessyInRecall` config toggle, or the caller explicitly browses into `messy/`.
        if (!filters.includeAll && !includeMessy && filters.pathPrefix?.startsWith("messy/") != true) {
            b.add(PrefixQuery(Term("path", "messy/")), BooleanClause.Occur.MUST_NOT); negatives++
        }
        // Captured session transcripts (`messy/sessions/`) are UNCONDITIONALLY excluded from recall —
        // stronger than the `messy/` quarantine: no includeAll / includeMessy / prefix-browse escape.
        // They hold raw transcripts (secret-adjacent) and must stay out of recall like `<private>`.
        b.add(PrefixQuery(Term("path", "messy/sessions/")), BooleanClause.Occur.MUST_NOT); negatives++
        if (positives == 0 && negatives == 0) return null
        // Lucene: a clause set with only MUST_NOT matches nothing — anchor with match-all.
        if (positives == 0) b.add(MatchAllDocsQuery(), BooleanClause.Occur.MUST)
        return b.build()
    }

    /** Distinct note paths matching [filter] (or all, when null), capped at [limit]. For Path A enumeration. */
    fun enumeratePaths(filter: Query?, limit: Int): List<String> = withSearcher { s ->
        if (s.indexReader.numDocs() == 0) return@withSearcher emptyList()
        val q = filter ?: MatchAllDocsQuery()
        val td = s.search(q, limit * 8) // over-fetch chunks; dedup to distinct notes below
        val sf = s.storedFields()
        val seen = LinkedHashSet<String>()
        for (sd in td.scoreDocs) { seen.add(sf.document(sd.doc).get("path")); if (seen.size >= limit) break }
        seen.toList().sorted()
    }

    fun analyzer(): StandardAnalyzer = analyzer

    private inline fun <T> withSearcher(block: (IndexSearcher) -> T): T {
        val s = searcherManager.acquire()
        try {
            return block(s)
        } finally {
            searcherManager.release(s)
        }
    }

    override fun close() {
        searcherManager.close()
        writer.close()
        directory.close()
        analyzer.close()
    }

    companion object {
        /**
         * The kNN vector field. Named rather than inlined because [pathsMissingVectors] detects the
         * embedding backlog by NEGATING its existence: a rename that missed one site would make the
         * query match nothing, the `MUST_NOT` therefore match everything, and the engine would
         * silently re-embed the whole vault on every boot — with no error anywhere.
         */
        const val VEC_FIELD = "vec"

        /** The stored copy of the same vector, kept so an unchanged chunk can be reused on reindex. */
        const val VEC_BYTES_FIELD = "vecBytes"

        fun floatsToBytes(arr: FloatArray): BytesRef {
            val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in arr) buf.putFloat(f)
            return BytesRef(buf.array())
        }

        fun bytesToFloats(ref: BytesRef): FloatArray {
            val buf = ByteBuffer.wrap(ref.bytes, ref.offset, ref.length).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(ref.length / 4) { buf.float }
        }

        /** Verify the index can be opened; the directory reader is closed immediately. */
        fun isReadable(dir: Path): Boolean = try {
            FSDirectory.open(dir).use { d -> if (DirectoryReader.indexExists(d)) DirectoryReader.open(d).close(); true }
        } catch (_: Throwable) {
            false
        }
    }
}
