package dev.svod.engine.graph

/**
 * A lightweight `[[wikilink]]` graph computed from current note content.
 *
 * This is the read-only graph the MCP `link`/`graph_query` tools serve. The full graph
 * subsystem — tag taxonomy, graph-view payloads, and **link-integrity on rename/move**
 * (transactionally rewriting references) — is Step 4; this is intentionally just resolution
 * + backlinks.
 */
class LinkGraph private constructor(
    private val outById: Map<String, List<ResolvedLink>>,
    private val backById: Map<String, List<String>>,
) {
    /** Outgoing links from [path]: each target plus the note it resolves to (or null). */
    fun outlinks(path: String): List<ResolvedLink> = outById[path].orEmpty()

    /** Notes that link to [path]. */
    fun backlinks(path: String): List<String> = backById[path].orEmpty()

    /** Unresolved targets referenced from [path] (broken links). */
    fun unresolved(path: String): List<String> = outlinks(path).filter { it.resolvedPath == null }.map { it.target }

    /** 1-hop neighborhood for graph_query. */
    fun neighborhood(path: String): Neighborhood =
        Neighborhood(path, outlinks(path), backlinks(path))

    /** Every note path in the snapshot (graph nodes). */
    fun nodePaths(): Set<String> = outById.keys

    /** Resolved edges (source → target path). */
    fun edges(): List<Pair<String, String>> =
        outById.entries.flatMap { (src, links) -> links.mapNotNull { it.resolvedPath?.let { tgt -> src to tgt } } }

    /** Unresolved edges (source → raw target). */
    fun unresolvedEdges(): List<Pair<String, String>> =
        outById.entries.flatMap { (src, links) -> links.filter { it.resolvedPath == null }.map { src to it.target } }

    data class ResolvedLink(val target: String, val resolvedPath: String?)
    data class Neighborhood(val path: String, val outlinks: List<ResolvedLink>, val backlinks: List<String>)

    companion object {
        /** Build the graph from a snapshot of note path → content. */
        fun build(notes: Map<String, String>): LinkGraph {
            val paths = notes.keys
            // Resolution indexes: exact path (with/without .md) and unique basename.
            val byPath = HashSet(paths)
            val byBasename = HashMap<String, MutableList<String>>()
            for (p in paths) {
                val base = p.substringAfterLast('/').removeSuffix(".md").lowercase()
                byBasename.getOrPut(base) { mutableListOf() }.add(p)
            }

            fun resolve(rawTarget: String): String? {
                val target = rawTarget.substringBefore('#').substringBefore('|').trim().replace('\\', '/').trimStart('/')
                if (target.isEmpty()) return null
                if (target in byPath) return target
                if ("$target.md" in byPath) return "$target.md"
                val matches = byBasename[target.substringAfterLast('/').removeSuffix(".md").lowercase()]
                return if (matches != null && matches.size == 1) matches.single() else null
            }

            val out = HashMap<String, List<ResolvedLink>>()
            val back = HashMap<String, MutableList<String>>()
            for ((path, content) in notes) {
                val links = WikiLinks.extract(content).map { ResolvedLink(it, resolve(it)) }
                out[path] = links
                for (l in links) if (l.resolvedPath != null) back.getOrPut(l.resolvedPath) { mutableListOf() }.add(path)
            }
            return LinkGraph(out, back)
        }
    }
}

/** Extracts canonical `[[wikilink]]` targets, dropping the `|alias` and tolerating `#heading`. */
object WikiLinks {
    private val LINK = Regex("\\[\\[([^\\[\\]]+?)]]")

    fun extract(content: String): List<String> =
        LINK.findAll(content).map { clean(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()

    /**
     * Canonical link target: drop the `[[target|alias]]` display alias (split on the first UNESCAPED
     * `|`) and normalize a trailing slash on the path, so `[[a/b/|Label]]`, `[[a/b/]]` and `[[a/b]]`
     * all reference the note `a/b`. A `#heading` suffix is preserved. Without this the stored target
     * keeps the alias and trailing slash, which is shown verbatim AND never resolves.
     */
    fun clean(inner: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length && inner[i + 1] == '|') { sb.append('|'); i += 2; continue }
            if (c == '|') break
            sb.append(c); i++
        }
        val t = sb.toString().trim()
        val hash = t.indexOf('#')
        return if (hash < 0) t.trimEnd('/') else t.substring(0, hash).trimEnd('/') + t.substring(hash)
    }
}
