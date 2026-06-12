package dev.svod.engine.migrate

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
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

    suspend fun import(source: Path, engine: SvodEngine, author: Author = Author("obsidian-import", "import@svod.localhost"), into: String = ""): Result {
        val base = source.normalize()
        val files = collectFiles(base)
        val imported = ArrayList<String>()
        val unchanged = ArrayList<String>()
        val skipped = ArrayList<String>()

        val prefix = into.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        for (file in files) {
            val rel = prefix + base.relativize(file).toString().replace('\\', '/')
            val incoming = Files.readAllBytes(file)

            val existing = engine.readBytes(rel)
            if (existing != null) {
                if (existing.contentEquals(incoming)) unchanged.add(rel) else skipped.add(rel)
                continue
            }

            // New file: markdown goes through the text path (so it is secret-scanned and indexed);
            // everything else through the binary path (attachments are stored, not embedded).
            val outcome = if (rel.endsWith(".md")) {
                engine.write(rel, String(incoming, UTF_8), expectedRevision = null, author = author)
            } else {
                engine.writeBytes(rel, incoming, expectedRevision = null, author = author)
            }
            when (outcome) {
                is WriteOutcome.Success -> imported.add(rel)
                else -> skipped.add(rel) // blocked by secret scan, or a race
            }
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
