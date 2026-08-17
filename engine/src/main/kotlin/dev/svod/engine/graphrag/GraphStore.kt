package dev.svod.engine.graphrag

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persists the derived graph to a **sidecar** directory (`.svod/graph/`), deliberately separate from
 * the Lucene index next door: nothing here may ever require touching `.svod/index`, because a schema
 * change there would force a reindex of ~79k chunks (`architecture-graphrag.md` §6).
 *
 * Everything written here is derived data. Deleting the directory is always safe — the feature simply
 * reports `NOT_BUILT` until the next build. That is also how corruption is handled: a load failure is
 * "never built", never an exception to the caller (test A7).
 */
class GraphStore(private val dir: Path) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val metaFile get() = dir.resolve("meta.json")
    private val graphFile get() = dir.resolve("graph.json")
    private val communitiesFile get() = dir.resolve("communities.json")
    private val centroidsFile get() = dir.resolve("centroids.json")

    data class Loaded(
        val meta: GraphMeta,
        val graph: NoteGraph,
        val levels: List<CommunityLevel>,
        val centroids: Map<String, FloatArray>,
    )

    /** Returns null when nothing usable is on disk — missing, partial, corrupt, or an old layout. */
    fun load(): Loaded? = try {
        val meta = json.decodeFromString<GraphMeta>(Files.readString(metaFile))
        // A derived cache is cheaper to rebuild than to migrate, so a layout bump just invalidates.
        if (meta.version != GraphMeta.SIDECAR_VERSION) null
        else Loaded(
            meta = meta,
            graph = json.decodeFromString<NoteGraph>(Files.readString(graphFile)),
            levels = json.decodeFromString<List<CommunityLevel>>(Files.readString(communitiesFile)),
            centroids = if (Files.exists(centroidsFile)) {
                json.decodeFromString<Map<String, List<Float>>>(Files.readString(centroidsFile))
                    .mapValues { it.value.toFloatArray() }
            } else {
                emptyMap()
            },
        )
    } catch (e: Exception) {
        null
    }

    /**
     * Writes the whole sidecar. Each file lands via write-temp-then-move, so a crash mid-write leaves
     * either the previous file or none — never a half-parsed one.
     */
    fun save(meta: GraphMeta, graph: NoteGraph, levels: List<CommunityLevel>, centroids: Map<String, FloatArray>) {
        Files.createDirectories(dir)
        writeAtomic(graphFile, json.encodeToString(graph))
        writeAtomic(communitiesFile, json.encodeToString(levels))
        writeAtomic(centroidsFile, json.encodeToString(centroids.mapValues { it.value.toList() }))
        // meta LAST: its presence is what makes a sidecar loadable, so it must never appear before the
        // files it describes.
        writeAtomic(metaFile, json.encodeToString(meta))
    }

    /** Removes the sidecar, so a failed rebuild cannot keep serving data it no longer describes. */
    fun clear() {
        if (!Files.isDirectory(dir)) return
        Files.list(dir).use { s -> s.forEach { runCatching { Files.deleteIfExists(it) } } }
    }

    private fun writeAtomic(target: Path, content: String) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
