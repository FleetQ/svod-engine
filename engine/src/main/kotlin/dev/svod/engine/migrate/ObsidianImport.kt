package dev.svod.engine.migrate

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
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

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
 *
 * Symlinks are **skipped by default** (recording each in `skipped`) — a git-backed store can't hold a
 * link to a path outside itself. Pass `followSymlinks = true` to instead **materialize** them: file
 * links are copied as files, directory links are descended into and their contents copied under the
 * link's path. Loops are detected by the walker and recorded as skipped. This is the one-shot way to
 * pull in a vault whose notes are symlinks to documents living in other projects.
 */
object ObsidianImport {

    private val log = LoggerFactory.getLogger(ObsidianImport::class.java)

    /** imported = newly written, unchanged = already identical, skipped = present-but-differs / blocked / unreadable. */
    data class Result(val imported: List<String>, val unchanged: List<String>, val skipped: List<String>)

    /** Files per batch commit — bounds in-flight memory while collapsing a big import to few commits. */
    private const val CHUNK = 512

    private class Walk(val files: List<Path>, val skipped: List<String>)

    suspend fun import(source: Path, engine: SvodEngine, author: Author = Author("obsidian-import", "import@svod.localhost"), into: String = "", followSymlinks: Boolean = false): Result {
        val base = source.normalize()
        val prefix = into.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val walk = collectFiles(base, prefix, followSymlinks)
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

    private fun collectFiles(base: Path, prefix: String, followSymlinks: Boolean): Walk {
        val out = ArrayList<Path>()
        val skipped = ArrayList<String>()
        // Default: walkFileTree does NOT follow symlinks, so a symlink — even to a directory — arrives
        // via visitFile with isSymbolicLink() true, and we SKIP it (recording the link): a git store
        // can't hold a link out of itself, and reading a dir-link as a file throws "Is a directory".
        //
        // followSymlinks=true adds FOLLOW_LINKS: dir links now arrive via preVisitDirectory and are
        // descended into; file links resolve and arrive as normal files via visitFile. The walker
        // tracks visited directories by file-key and raises FileSystemLoopException (→ visitFileFailed)
        // on a cycle, so loops are recorded as skipped rather than spinning. Links may point outside
        // the source root — that is the point here (notes are links into other projects).
        val options = if (followSymlinks) EnumSet.of(FileVisitOption.FOLLOW_LINKS) else EnumSet.noneOf(FileVisitOption::class.java)
        Files.walkFileTree(base, options, Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != base && dir.fileName.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (name.startsWith(".")) return FileVisitResult.CONTINUE
                if (!followSymlinks && (attrs.isSymbolicLink() || Files.isSymbolicLink(file))) {
                    log.info("import: skipping symlink {}", file)
                    skipped += prefix + base.relativize(file).toString().replace('\\', '/')
                    return FileVisitResult.CONTINUE
                }
                out.add(file)
                return FileVisitResult.CONTINUE
            }

            // A broken symlink, a detected symlink loop, or a permission error on an entry must not
            // abort the walk — record it and continue.
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                log.warn("import: skipping inaccessible entry {}: {}", file, exc.message)
                skipped += prefix + base.relativize(file).toString().replace('\\', '/')
                return FileVisitResult.CONTINUE
            }
        })
        return Walk(out, skipped)
    }
}
