package dev.svod.engine.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Versioned index metadata, persisted as JSON beside the Lucene index.
 *
 * Drives two reconciliation triggers:
 *  - [schemaVersion]/[embeddingModel]/[embeddingDim] mismatch ⇒ full reindex (migration)
 *  - [headCommit] vs git HEAD divergence ⇒ incremental sync / self-heal
 */
@Serializable
data class IndexMeta(
    val schemaVersion: Int,
    val embeddingModel: String,
    val embeddingDim: Int,
    val headCommit: String? = null,
) {
    /**
     * True if the index is compatible (no reindex needed) with the active embedder. A [dim] of 0 is
     * a wildcard — a remote embedder whose dimension hasn't been probed yet resumes against its
     * existing index (same model) rather than forcing a wipe; the real dim is recorded after the
     * first successful embed.
     */
    fun compatibleWith(schemaVersion: Int, model: String, dim: Int): Boolean =
        this.schemaVersion == schemaVersion && embeddingModel == model &&
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
