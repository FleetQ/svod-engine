package dev.svod.engine.migrate

import dev.svod.engine.core.Author
import dev.svod.engine.core.BatchEntry
import dev.svod.engine.core.SvodEngine
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Imports an existing Obsidian vault into Svod as a one-shot migration. Because Svod's source of
 * truth IS markdown + YAML frontmatter, import is a faithful copy through the normal write path:
 * frontmatter and `[[wikilinks]]` are preserved verbatim, every file becomes an attributed git
 * commit, and the index/graph pick it up. Zero lock-in by construction (export is just the git tree).
 *
 * Beyond markdown it carries **attachments** (images, PDF, …) byte-for-byte via the engine's
 * binary write path, so `![[image.png]]` references keep resolving. Obsidian's own config
 * (`.obsidian/`) and other dot-directories are skipped.
 *
 * Import is **idempotent**: re-running reconciles rather than duplicating. A file already present
 * with identical content is `unchanged`; one present with *different* content is `skipped` (left
 * exactly as-is — a migration never clobbers local edits).
 */
object ObsidianImport {

    private val log = LoggerFactory.getLogger(ObsidianImport::class.java)

    /** imported = newly written, unchanged = already identical, skipped = present-but-differs / blocked / unreadable. */
    data class Result(val imported: List<String>, val unchanged: List<String>, val skipped: List<String>)

    /** Files per batch commit — bounds in-flight memory while collapsing a big import to few commits. */
    private const val CHUNK = 512

    private class Walk(val files: List<Path>, val skipped: List<String>)

    suspend fun import(source: Path, engine: SvodEngine, author: Author = Author("obsidian-import", "import@svod.localhost"), into: String = ""): Result {
        val base = source.normalize()
        val prefix = into.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val walk = collectFiles(base, prefix)
        val imported = ArrayList<String>()
        val unchanged = ArrayList<String>()
        val skipped = ArrayList(walk.skipped)

        // Import in chunks, each a single batch commit — markdown as text (secret-scanned + indexed),
        // everything else as raw bytes (attachments stored, not embedded). One commit per chunk
        // instead of one per file is the difference between a few commits and tens of thousands.
        for (chunk in walk.files.chunked(CHUNK)) {
            // A single unreadable entry must not abort the whole import: read failures are recorded
            // in `skipped` and the walk continues (only a missing source — checked by the caller — is fatal).
            val entries = chunk.mapNotNull { file ->
                val rel = prefix + base.relativize(file).toString().replace('\\', '/')
                try {
                    val bytes = Files.readAllBytes(file)
                    if (rel.endsWith(".md")) BatchEntry.Text(rel, String(bytes, UTF_8)) else BatchEntry.Bytes(rel, bytes)
                } catch (e: IOException) {
                    log.warn("import: skipping unreadable entry {}: {}", rel, e.message)
                    skipped += rel
                    null
                }
            }
            if (entries.isEmpty()) continue
            val r = engine.writeBatch(entries, author, "import: ${entries.size} files")
            imported += r.written; unchanged += r.unchanged; skipped += r.skipped
        }
        return Result(imported.sorted(), unchanged.sorted(), skipped.distinct().sorted())
    }

    private fun collectFiles(base: Path, prefix: String): Walk {
        val out = ArrayList<Path>()
        val skipped = ArrayList<String>()
        // walkFileTree does NOT follow symlinks (FOLLOW_LINKS unset), so a symlink — even to a
        // directory — arrives via visitFile with isSymbolicLink() true. We SKIP all symlinks: it
        // avoids reading a dir as a file ("Is a directory"), symlink loops, and links escaping the
        // source root. Each skipped link is recorded.
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != base && dir.fileName.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (name.startsWith(".")) return FileVisitResult.CONTINUE
                if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    log.info("import: skipping symlink {}", file)
                    skipped += prefix + base.relativize(file).toString().replace('\\', '/')
                    return FileVisitResult.CONTINUE
                }
                out.add(file)
                return FileVisitResult.CONTINUE
            }

            // A broken symlink / permission error on an entry must not abort the walk.
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                log.warn("import: skipping inaccessible entry {}: {}", file, exc.message)
                skipped += prefix + base.relativize(file).toString().replace('\\', '/')
                return FileVisitResult.CONTINUE
            }
        })
        return Walk(out, skipped)
    }
}
