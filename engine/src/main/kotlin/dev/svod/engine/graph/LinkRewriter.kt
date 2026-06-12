package dev.svod.engine.graph

/**
 * Rewrites `[[wikilinks]]` when a note moves, so references keep resolving to it.
 *
 * Handles both link styles and preserves any `#heading` / `|alias` suffix:
 *  - path-style `[[a/foo]]` / `[[a/foo.md]]` → updated to the new path,
 *  - basename-style `[[foo]]` → updated to the new basename, but only when that basename
 *    is unambiguous across the vault (otherwise we leave it, since it wasn't resolving to a
 *    single note anyway).
 *
 * Constructed from the pre-move set of note paths so ambiguity is judged against reality.
 */
class LinkRewriter(allPaths: Set<String>) {

    private val basenameCount: Map<String, Int> = allPaths.groupingBy { basename(it) }.eachCount()

    /** True if [content] contains at least one wikilink that points to [fromPath]. */
    fun linksTo(content: String, fromPath: String): Boolean =
        WikiLinks.extract(content).any { pointsTo(it, fromPath) }

    /** Rewrite links pointing at [fromPath] to point at [toPath]. Returns (newContent, changed). */
    fun rewrite(content: String, fromPath: String, toPath: String): Pair<String, Boolean> {
        var changed = false
        val out = LINK.replace(content) { m ->
            val inner = m.groupValues[1]
            val targetPart = inner.takeWhile { it != '#' && it != '|' }
            val suffix = inner.substring(targetPart.length) // "", "#h", "|alias", "#h|alias"
            if (pointsTo(targetPart, fromPath)) {
                changed = true
                "[[" + newTargetFor(targetPart, fromPath, toPath) + suffix + "]]"
            } else {
                m.value
            }
        }
        return out to changed
    }

    private fun pointsTo(target: String, fromPath: String): Boolean {
        val norm = normalize(target)
        if (norm == fromPath || "$norm.md" == fromPath) return true // path style
        // basename style: only when unambiguous and the target is bare (no slash)
        if (!norm.contains('/')) {
            val b = basename(fromPath)
            if (norm.equals(b, ignoreCase = true) && basenameCount[b] == 1) return true
        }
        return false
    }

    private fun newTargetFor(target: String, fromPath: String, toPath: String): String {
        val norm = normalize(target)
        return if (norm == fromPath || "$norm.md" == fromPath) toPath.removeSuffix(".md") // keep path style
        else basename(toPath) // keep basename style
    }

    private fun normalize(target: String): String = target.trim().replace('\\', '/').trimStart('/')

    private fun basename(path: String): String = path.substringAfterLast('/').removeSuffix(".md")

    companion object {
        private val LINK = Regex("\\[\\[([^\\[\\]]+?)]]")
    }
}
