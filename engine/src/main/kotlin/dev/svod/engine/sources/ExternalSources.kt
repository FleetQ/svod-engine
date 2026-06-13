package dev.svod.engine.sources

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * A registered **external source**: a filesystem path (file or directory) living OUTSIDE the vault
 * whose content is materialized into the vault under [into] and can be **re-synced** on demand.
 *
 * This is Svod's git-friendly answer to Obsidian's symlinked notes: a symlink can't live in a git
 * tree, so instead of linking we copy, and a source can be re-synced to pull later edits in. Sync
 * semantics are *external-wins-unless-locally-edited* (see [SourceSync]).
 *
 * [id] is derived deterministically from the absolute [path], so re-registering the same path is
 * idempotent. [followSymlinks] governs links found *inside* a directory source (the source root
 * itself is always resolved).
 */
@Serializable
data class ExternalSource(
    val id: String,
    val path: String,
    val into: String = "",
    val followSymlinks: Boolean = false,
    val lastSyncedAt: String? = null,
) {
    companion object {
        /** Deterministic id: a slug of the basename + a short hash of the absolute path (collision-safe). */
        fun idFor(absolutePath: String): String {
            val base = absolutePath.trimEnd('/').substringAfterLast('/').ifEmpty { "root" }
            val slug = base.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
                .trim('-').take(40).ifEmpty { "source" }
            val hash = MessageDigest.getInstance("SHA-256").digest(absolutePath.toByteArray())
                .take(4).joinToString("") { "%02x".format(it) }
            return "$slug-$hash"
        }
    }
}

/** Per-source outcome of a sync. `conflicts` = vault copy locally edited since last sync (left as-is). */
@Serializable
data class SourceSyncResult(
    val id: String,
    val created: List<String> = emptyList(),
    val updated: List<String> = emptyList(),
    val unchanged: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val orphaned: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Persistence for external-source registrations + per-source sync manifests, under the gitignored
 * `<root>/.svod/`. The registration list is `sources.json`; each source's last-synced state (vault
 * path → git blob id) is `source-manifests/<id>.json`. The manifest is how a re-sync tells "external
 * changed, vault untouched" (→ update) from "vault locally edited" (→ conflict).
 */
class ExternalSourceStore(root: Path) {
    private val dir: Path = root.resolve(".svod")
    private val file: Path = dir.resolve("sources.json")
    private val manifestDir: Path = dir.resolve("source-manifests")
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val lock = Any()

    fun list(): List<ExternalSource> = synchronized(lock) {
        if (!Files.isRegularFile(file)) emptyList()
        else json.decodeFromString(SOURCES, Files.readString(file))
    }

    fun get(id: String): ExternalSource? = list().firstOrNull { it.id == id }

    /** Upsert by id; returns the stored source. */
    fun put(source: ExternalSource): ExternalSource = synchronized(lock) {
        val current = if (Files.isRegularFile(file)) json.decodeFromString(SOURCES, Files.readString(file)) else emptyList()
        val next = current.filter { it.id != source.id } + source
        Files.createDirectories(dir)
        Files.writeString(file, json.encodeToString(SOURCES, next.sortedBy { it.id }))
        source
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        if (!Files.isRegularFile(file)) return false
        val current = json.decodeFromString(SOURCES, Files.readString(file))
        val next = current.filter { it.id != id }
        if (next.size == current.size) return false
        Files.writeString(file, json.encodeToString(SOURCES, next))
        Files.deleteIfExists(manifestDir.resolve("$id.json"))
        true
    }

    fun loadManifest(id: String): Map<String, String> = synchronized(lock) {
        val mf = manifestDir.resolve("$id.json")
        if (!Files.isRegularFile(mf)) emptyMap()
        else json.decodeFromString(MANIFEST, Files.readString(mf))
    }

    fun saveManifest(id: String, manifest: Map<String, String>): Unit = synchronized(lock) {
        Files.createDirectories(manifestDir)
        Files.writeString(manifestDir.resolve("$id.json"), json.encodeToString(MANIFEST, manifest))
    }

    private companion object {
        val SOURCES = ListSerializer(ExternalSource.serializer())
        val MANIFEST = MapSerializer(String.serializer(), String.serializer())
    }
}
