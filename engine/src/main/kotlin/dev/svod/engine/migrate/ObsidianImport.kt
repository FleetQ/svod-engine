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
 * Imports an existing Obsidian vault into Svod. Because Svod's source of truth IS markdown +
 * YAML frontmatter, import is a faithful copy through the normal write path: frontmatter and
 * `[[wikilinks]]` are preserved verbatim, every file becomes an attributed git commit, and
 * the index/graph pick it up. Zero lock-in by construction (export is just the git tree).
 *
 * Obsidian's own config (`.obsidian/`) and other dot-directories are skipped.
 */
object ObsidianImport {

    data class Result(val imported: List<String>, val skipped: List<String>)

    suspend fun import(source: Path, engine: SvodEngine, author: Author = Author("obsidian-import", "import@svod.localhost"), into: String = ""): Result {
        val base = source.normalize()
        val files = collectMarkdown(base)
        val imported = ArrayList<String>()
        val skipped = ArrayList<String>()

        val prefix = into.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        for (file in files) {
            val rel = prefix + base.relativize(file).toString().replace('\\', '/')
            val content = Files.readString(file, UTF_8)
            when (engine.write(rel, content, expectedRevision = null, author = author)) {
                is WriteOutcome.Success -> imported.add(rel)
                else -> skipped.add(rel) // already present, blocked by secret scan, etc.
            }
        }
        return Result(imported.sorted(), skipped.sorted())
    }

    private fun collectMarkdown(base: Path): List<Path> {
        val out = ArrayList<Path>()
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != base && dir.fileName.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.fileName.toString().endsWith(".md")) out.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return out
    }
}
