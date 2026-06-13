package dev.svod.engine.migrate

import dev.svod.engine.core.Author
import dev.svod.engine.core.BatchEntry
import dev.svod.engine.core.SvodEngine
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

    /** imported = newly written, unchanged = already identical, skipped = present-but-differs / blocked. */
    data class Result(val imported: List<String>, val unchanged: List<String>, val skipped: List<String>)

    /** Files per batch commit — bounds in-flight memory while collapsing a big import to few commits. */
    private const val CHUNK = 512

    suspend fun import(source: Path, engine: SvodEngine, author: Author = Author("obsidian-import", "import@svod.localhost"), into: String = ""): Result {
        val base = source.normalize()
        val files = collectFiles(base)
        val imported = ArrayList<String>()
        val unchanged = ArrayList<String>()
        val skipped = ArrayList<String>()

        val prefix = into.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        // Import in chunks, each a single batch commit — markdown as text (secret-scanned + indexed),
        // everything else as raw bytes (attachments stored, not embedded). One commit per chunk
        // instead of one per file is the difference between a few commits and tens of thousands.
        for (chunk in files.chunked(CHUNK)) {
            val entries = chunk.map { file ->
                val rel = prefix + base.relativize(file).toString().replace('\\', '/')
                val bytes = Files.readAllBytes(file)
                if (rel.endsWith(".md")) BatchEntry.Text(rel, String(bytes, UTF_8)) else BatchEntry.Bytes(rel, bytes)
            }
            val r = engine.writeBatch(entries, author, "import: ${entries.size} files")
            imported += r.written; unchanged += r.unchanged; skipped += r.skipped
        }
        return Result(imported.sorted(), unchanged.sorted(), skipped.sorted())
    }

    private fun collectFiles(base: Path): List<Path> {
        val out = ArrayList<Path>()
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != base && dir.fileName.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!file.fileName.toString().startsWith(".")) out.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return out
    }
}
