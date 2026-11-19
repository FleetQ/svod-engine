package dev.svod.engine.migrate

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.graph.WikiLinks
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/**
 * Imports a Basic Memory project into Svod. Basic Memory stores plain markdown with YAML
 * frontmatter, observation lines (`- [category] text #tag`) and relation lines
 * (`- rel_type [[Target]]`), so the import is [ObsidianImport]'s faithful copy (same walk, same
 * batch writes, same idempotency and no-clobber rules) with one addition: Basic Memory relations
 * point at a note's frontmatter `title` or `permalink`, which Svod's link graph does not resolve.
 *
 * A wikilink is rewritten only when it does NOT already resolve by Svod's own rules (exact path,
 * path + `.md`, unique filename — over the imported file set) AND its target matches the title or
 * permalink of exactly one imported note. It becomes `[[path/without-ext#heading|Original]]`, so
 * the text a reader sees stays the same. Everything else — observations, relation-type words,
 * frontmatter, ambiguous or unmatched links — is copied verbatim. The output is deterministic, so a
 * re-import reports `unchanged`.
 */
object BasicMemoryImport {

    private val LINK = Regex("\\[\\[([^\\[\\]]+?)]]")

    suspend fun import(
        source: Path,
        engine: SvodEngine,
        author: Author = Author("basic-memory-import", "import@svod.localhost"),
        into: String = "",
        followSymlinks: Boolean = false,
    ): ObsidianImport.Result {
        val base = source.normalize()
        val prefix = ObsidianImport.prefixOf(into)
        val walk = ObsidianImport.collectFiles(base, prefix, followSymlinks)

        // Pass 1: the imported path set (for "already resolves") and title/permalink → paths.
        val paths = HashSet<String>()
        val byName = HashMap<String, MutableSet<String>>()
        for (file in walk.files) {
            val rel = ObsidianImport.relPath(base, prefix, file)
            paths += rel
            if (!rel.endsWith(".md")) continue
            val fm = runCatching { frontmatter(String(Files.readAllBytes(file), UTF_8)) }.getOrNull() ?: continue
            for (key in listOf("title", "permalink")) {
                // Scalars load as the literal text (see [yaml]), so `title: 2024-01-05` or `permalink: 007` match as written.
                val v = (fm[key] as? String)?.trim()?.lowercase()
                if (!v.isNullOrEmpty()) byName.getOrPut(v) { sortedSetOf() }.add(rel)
            }
        }
        val byBasename = HashMap<String, MutableList<String>>()
        for (p in paths) byBasename.getOrPut(p.substringAfterLast('/').removeSuffix(".md").lowercase()) { mutableListOf() }.add(p)

        // Same rules as LinkGraph.buildFromTargets.
        fun resolves(rawTarget: String): Boolean {
            val target = rawTarget.substringBefore('#').substringBefore('|').trim().replace('\\', '/').trimStart('/')
            if (target.isEmpty()) return false
            if (target in paths || "$target.md" in paths) return true
            return byBasename[target.substringAfterLast('/').removeSuffix(".md").lowercase()]?.size == 1
        }

        // Pass 2: the shared import (over the same walk) with the link rewrite applied to the
        // markdown body; the frontmatter block is copied verbatim.
        return ObsidianImport.importWith(source, engine, author, into, followSymlinks, walk) { _, text ->
            val bodyStart = FRONTMATTER.find(text)?.range?.last?.plus(1) ?: 0
            text.substring(0, bodyStart) + LINK.replace(text.substring(bodyStart)) { m ->
                val inner = m.groupValues[1]
                val target = WikiLinks.clean(inner)
                if (target.isEmpty() || resolves(target)) return@replace m.value
                val alias = aliasOf(inner)
                // A table cell writes `[[Target\|Alias]]`; clean() unescapes that pipe into the target.
                val tableEscaped = alias == null && "\\|" in inner
                val linkPart = if (tableEscaped) target.substringBefore('|') else target
                val hash = linkPart.indexOf('#')
                val name = (if (hash < 0) linkPart else linkPart.substring(0, hash)).trim().lowercase()
                val heading = if (hash < 0) "" else linkPart.substring(hash)
                val match = byName[name]?.singleOrNull() ?: return@replace m.value
                // A path with `#`, `|`, `[` or `]` cannot be written as a wikilink target that resolves back to it.
                if (match.any { it in UNLINKABLE }) return@replace m.value
                val (sep, display) = when {
                    alias != null -> "|" to alias
                    tableEscaped -> "\\|" to inner.substringAfter("\\|")
                    else -> "|" to inner.trim()
                }
                "[[${match.removeSuffix(".md")}$heading$sep$display]]"
            }
        }
    }

    private const val UNLINKABLE = "#|[]"

    /** The `|alias` part of a wikilink's inner text (after the first unescaped `|`), or null. */
    private fun aliasOf(inner: String): String? {
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length && inner[i + 1] == '|') { i += 2; continue }
            if (c == '|') return inner.substring(i + 1)
            i++
        }
        return null
    }

    /** The leading `---` frontmatter block (optional BOM, YAML in group 1, closing fence line included). */
    private val FRONTMATTER = Regex("^\\uFEFF?---[ \\t]*\\r?\\n(?:(.*?)\\r?\\n)?---[ \\t]*(?:\\r?\\n|$)", RegexOption.DOT_MATCHES_ALL)

    /** The leading `---` YAML frontmatter as a map; null when absent or unparseable. */
    private fun frontmatter(text: String): Map<*, *>? {
        val m = FRONTMATTER.find(text) ?: return null
        return try {
            yaml().load<Any?>(m.groupValues[1]) as? Map<*, *>
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A safe loader with no implicit scalar resolvers: every plain scalar loads as its literal text,
     * so a date- or number-looking `title`/`permalink` (`2024-01-05`, `007`, `1.10`) keeps the exact
     * spelling a `[[wikilink]]` uses instead of becoming a Date or a reformatted number.
     */
    private fun yaml(): Yaml {
        val loader = LoaderOptions()
        val dumper = DumperOptions()
        return Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader, LiteralResolver())
    }

    private class LiteralResolver : Resolver() {
        override fun addImplicitResolvers() {}
    }
}
