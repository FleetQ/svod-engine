package dev.svod.engine.sources

import dev.svod.engine.core.Author
import dev.svod.engine.core.BatchEntry
import dev.svod.engine.core.SvodEngine
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.EnumSet

/**
 * Re-synces an [ExternalSource] into the vault. Semantics are **external-wins-unless-locally-edited**:
 *
 *  - vault path absent → **create**.
 *  - vault content already equals the external content → **unchanged**.
 *  - they differ AND the vault copy still matches the last-synced manifest (i.e. only the external
 *    side changed) → **update** (the external edit flows in).
 *  - they differ AND the vault copy no longer matches the manifest (someone edited it in the vault
 *    since the last sync) → **conflict**: left exactly as-is, never clobbered.
 *  - a path that was synced before but is gone from the source now → **orphaned**: reported, left in
 *    the vault (deletions are not propagated in v1).
 *
 * Writes go through a single overwriting batch (one commit per sync); secret-scanner-blocked `.md`
 * entries are reported as `skipped`. The manifest (vault path → git blob id) is updated after.
 */
class SourceSync(private val engine: SvodEngine, private val store: ExternalSourceStore) {

    private val log = LoggerFactory.getLogger(SourceSync::class.java)
    private val author = Author("external-source-sync", "sync@svod.localhost")

    suspend fun sync(source: ExternalSource): SourceSyncResult {
        val base = Paths.get(source.path).normalize()
        if (!Files.exists(base)) {
            return SourceSyncResult(source.id, error = "source path not found: ${source.path}")
        }
        val prefix = source.into.trim('/').let { if (it.isEmpty()) "" else "$it/" }

        val files: Map<String, ByteArray> = try {
            collect(base, prefix, source.followSymlinks)
        } catch (e: IOException) {
            return SourceSyncResult(source.id, error = "could not read source: ${e.message}")
        }

        val manifest = store.loadManifest(source.id)
        val newManifest = HashMap<String, String>()
        val unchanged = ArrayList<String>()
        val conflicts = ArrayList<String>()
        val toWrite = ArrayList<BatchEntry>()
        val createCandidates = HashSet<String>()
        val updateCandidates = HashSet<String>()
        // The on-disk revision each to-be-written path had at classify time. Passed to the batch so the
        // write re-validates against it — a vault edit landing between here and the write is a conflict,
        // not a clobber (closes the classify→write race; create candidates expect "absent" = null).
        val expected = HashMap<String, String?>()

        for ((vaultPath, bytes) in files) {
            val extRev = engine.blobId(bytes)
            val cur = engine.read(vaultPath)   // revision is blobId(bytes), valid for binary too
            when {
                cur == null -> { toWrite += entry(vaultPath, bytes); createCandidates += vaultPath; expected[vaultPath] = null; newManifest[vaultPath] = extRev }
                cur.revision == extRev -> { unchanged += vaultPath; newManifest[vaultPath] = extRev }
                manifest[vaultPath] == cur.revision -> { toWrite += entry(vaultPath, bytes); updateCandidates += vaultPath; expected[vaultPath] = cur.revision; newManifest[vaultPath] = extRev }
                else -> { conflicts += vaultPath; manifest[vaultPath]?.let { newManifest[vaultPath] = it } }
            }
        }
        val gone = manifest.keys.filter { it !in files.keys }

        var written = emptySet<String>()
        var secretSkipped = emptyList<String>()
        if (toWrite.isNotEmpty()) {
            val r = engine.writeBatch(toWrite, author, "sync ${source.id}: ${toWrite.size} files", overwrite = true, expected = expected)
            written = r.written.toSet()
            secretSkipped = r.skipped
            // A secret-blocked entry was not written — don't claim it, and drop it from the manifest.
            for (s in r.skipped) newManifest.remove(s)
            // A file edited in the vault during the sync window — surface it as a conflict (never
            // clobbered), keep tracking it at its last-synced revision rather than the external one.
            for (c in r.conflicts) {
                createCandidates.remove(c); updateCandidates.remove(c)
                conflicts += c
                newManifest.remove(c)
                manifest[c]?.let { newManifest[c] = it }
            }
        }

        // A file gone from the source: pruned (soft-deleted) if the source opts in AND the vault copy
        // is untouched since the last sync; otherwise left in place (orphaned). A locally-edited copy
        // is never deleted, mirroring the update-vs-conflict guard above.
        val deleted = ArrayList<String>()
        val orphaned = ArrayList<String>()
        for (path in gone) {
            val cur = engine.read(path)
            if (source.prune && cur != null && manifest[path] == cur.revision) {
                val outcome = engine.delete(path, cur.revision, author)
                if (outcome is dev.svod.engine.core.WriteOutcome.Success) { deleted += path; newManifest.remove(path) }
                else { orphaned += path; manifest[path]?.let { newManifest[path] = it } }
            } else {
                orphaned += path
                if (cur != null) manifest[path]?.let { newManifest[path] = it }  // keep tracking a still-present orphan
            }
        }

        store.saveManifest(source.id, newManifest)
        store.put(source.copy(lastSyncedAt = Instant.now().toString()))

        return SourceSyncResult(
            id = source.id,
            created = createCandidates.filter { it in written }.sorted(),
            updated = updateCandidates.filter { it in written }.sorted(),
            unchanged = unchanged.sorted(),
            conflicts = conflicts.sorted(),
            orphaned = orphaned.sorted(),
            deleted = deleted.sorted(),
            skipped = secretSkipped.sorted(),
        )
    }

    private fun entry(vaultPath: String, bytes: ByteArray): BatchEntry =
        if (vaultPath.endsWith(".md")) BatchEntry.Text(vaultPath, String(bytes, UTF_8)) else BatchEntry.Bytes(vaultPath, bytes)

    /** External path → bytes, keyed by the in-vault path (prefix + relative). A file source is one entry. */
    private fun collect(base: Path, prefix: String, follow: Boolean): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        if (Files.isRegularFile(base)) {
            out[prefix + base.fileName.toString().replace('\\', '/')] = Files.readAllBytes(base)
            return out
        }
        val options = if (follow) EnumSet.of(FileVisitOption.FOLLOW_LINKS) else EnumSet.noneOf(FileVisitOption::class.java)
        Files.walkFileTree(base, options, Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Skip dot-dirs (a project's .git/.obsidian must never be pulled into the vault).
                if (dir != base && dir.fileName.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (name.startsWith(".")) return FileVisitResult.CONTINUE
                if (!follow && (attrs.isSymbolicLink() || Files.isSymbolicLink(file))) return FileVisitResult.CONTINUE
                try {
                    out[prefix + base.relativize(file).toString().replace('\\', '/')] = Files.readAllBytes(file)
                } catch (e: IOException) {
                    log.warn("source sync: skipping unreadable {}: {}", file, e.message)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                log.warn("source sync: skipping inaccessible {}: {}", file, exc.message)
                return FileVisitResult.CONTINUE
            }
        })
        return out
    }
}
