package dev.svod.engine.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Versioned index metadata, persisted as JSON beside the Lucene index.
 *
 * Drives two reconciliation triggers:
 *  - [schemaVersion]/[embeddingModel]/[embeddingDim]/[passagePrefix] mismatch ⇒ full reindex (migration)
 *  - [headCommit] vs git HEAD divergence ⇒ incremental sync / self-heal
 */
@Serializable
data class IndexMeta(
    val schemaVersion: Int,
    val embeddingModel: String,
    val embeddingDim: Int,
    val headCommit: String? = null,
    /**
     * The prefix the indexed passages were embedded with. Part of the compatibility key because it
     * changes the vectors: an index built with "passage: " cannot be searched by queries embedded
     * without it, and that mismatch is silent — retrieval simply gets worse.
     *
     * Defaults to e5's "passage: " on purpose. Every index written before this field existed was
     * built by an embedder that hardcoded it, so the default states what those indexes actually
     * contain rather than what a fresh one would. That makes the migration precise: an e5 vault
     * keeps its vectors, and only a vault whose model does not want the prefix (bge-m3 via Ollama)
     * re-embeds.
     */
    val passagePrefix: String = ModelPrefixes.E5_PASSAGE,
) {
    /**
     * True if the index is compatible (no reindex needed) with the active embedder. A [dim] of 0 is
     * a wildcard — a remote embedder whose dimension hasn't been probed yet resumes against its
     * existing index (same model) rather than forcing a wipe; the real dim is recorded after the
     * first successful embed.
     */
    fun compatibleWith(schemaVersion: Int, model: String, dim: Int, passagePrefix: String): Boolean =
        this.schemaVersion == schemaVersion && embeddingModel == model &&
            // A stored dim of 0 means this index holds no vectors at all (BM25-only), and a prefix
            // only ever shows up inside a vector — so comparing it there would wipe every
            // lexical-only vault on upgrade for a difference that cannot exist in its data.
            (embeddingDim == 0 || this.passagePrefix == passagePrefix) &&
            (embeddingDim == dim || dim == 0 || embeddingDim == 0)

    companion object {
        const val SCHEMA_VERSION = 1
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun load(file: Path): IndexMeta? =
            if (Files.isRegularFile(file)) json.decodeFromString(serializer(), Files.readString(file)) else null

        fun save(file: Path, meta: IndexMeta) {
            Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(serializer(), meta))
        }
    }
}
