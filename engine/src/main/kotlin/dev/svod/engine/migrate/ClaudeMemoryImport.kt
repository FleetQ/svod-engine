package dev.svod.engine.migrate

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import java.nio.file.Path

/**
 * Imports a Claude Code auto-memory folder (`~/.claude/projects/<project>/memory/`) into Svod, one
 * note per memory file, under `into` (default `claude-memory`). It is [ObsidianImport]'s faithful
 * copy (same walk, batch writes, idempotency and no-clobber rules) with three additions:
 *
 * - the top-level `MEMORY.md` index is not imported (a `MEMORY.md` in a subfolder is);
 * - a `[[name]]` link to another memory's frontmatter `name` becomes `[[path|name]]`, using
 *   [BasicMemoryImport]'s rewrite rules (links that already resolve are left alone);
 * - a `tags: [claude-memory, <type>]` line is added to the frontmatter, the type taken from
 *   `metadata.type` or a top-level `type`. Frontmatter that is missing, unparseable or already has a
 *   `tags`/`tag` key is kept byte for byte.
 *
 * The output is deterministic, so a re-import reports `unchanged`.
 */
object ClaudeMemoryImport {

    const val TAG = "claude-memory"
    private const val INDEX = "MEMORY.md"

    suspend fun import(
        source: Path,
        engine: SvodEngine,
        author: Author = Author("claude-memory-import", "import@svod.localhost"),
        into: String = "claude-memory",
        followSymlinks: Boolean = false,
    ): ObsidianImport.Result {
        val base = source.normalize()
        val prefix = ObsidianImport.prefixOf(into)
        val all = ObsidianImport.collectFiles(base, prefix, followSymlinks)
        val walk = ObsidianImport.Walk(
            all.files.filterNot { it.parent?.normalize() == base && it.fileName?.toString() == INDEX },
            all.skipped,
        )
        val rewrite = BasicMemoryImport.linkRewriter(base, prefix, walk, listOf("name"))
        return ObsidianImport.importWith(source, engine, author, into, followSymlinks, walk) { _, text ->
            withTags(rewrite(text))
        }
    }

    /** [text] with a `tags:` line added before the closing frontmatter fence, or unchanged (see the class doc). */
    internal fun withTags(text: String): String {
        val m = BasicMemoryImport.FRONTMATTER.find(text) ?: return text
        val fm = BasicMemoryImport.frontmatter(text) ?: return text
        if ("tags" in fm || "tag" in fm) return text
        val type = ((fm["metadata"] as? Map<*, *>)?.get("type") as? String ?: fm["type"] as? String)
            ?.lowercase()?.filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
            ?.takeIf { it.isNotEmpty() }
        val tags = listOfNotNull(TAG, type).distinct().joinToString(", ")
        val fence = m.range.first + m.value.lastIndexOf("---")
        val firstLineEnd = text.indexOf('\n')
        val eol = if (firstLineEnd > 0 && text[firstLineEnd - 1] == '\r') "\r\n" else "\n"
        return text.substring(0, fence) + "tags: [$tags]$eol" + text.substring(fence)
    }
}
