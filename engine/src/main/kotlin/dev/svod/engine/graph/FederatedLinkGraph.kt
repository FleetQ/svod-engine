package dev.svod.engine.graph

/**
 * A `[[wikilink]]` graph that spans multiple vaults. Node ids are vault-qualified — `"vault:path"`.
 *
 * Resolution rules (architecture D5):
 *  - A **qualified** link `[[work:project]]` (where `work` is a known vault id) resolves in that
 *    vault. This is how a note in one vault references a note in another.
 *  - An **unqualified** link `[[project]]` resolves only within the *source note's own* vault — no
 *    cross-vault guessing, so an identically-named note elsewhere is never silently matched.
 *
 * Used to surface cross-vault backlinks (a work note linking a personal note shows up in the
 * personal note's backlinks) without the App API or MCP needing to know the resolution details.
 */
class FederatedLinkGraph private constructor(
    private val out: Map<String, List<Edge>>,
    private val back: Map<String, List<String>>,
) {
    /** A link target plus the global id ("vault:path") it resolves to, or null if unresolved. */
    data class Edge(val target: String, val resolvedGlobalId: String?)

    private fun gid(vault: String, path: String) = "$vault:$path"

    fun outlinks(vault: String, path: String): List<Edge> = out[gid(vault, path)].orEmpty()

    /** Global ids ("vault:path") of notes — in any vault — that link to this note. */
    fun backlinks(vault: String, path: String): List<String> = back[gid(vault, path)].orEmpty()

    fun unresolved(vault: String, path: String): List<String> =
        outlinks(vault, path).filter { it.resolvedGlobalId == null }.map { it.target }

    companion object {
        /** Build from `vaultId → (path → content)` across every vault. */
        fun build(vaults: Map<String, Map<String, String>>): FederatedLinkGraph {
            // Per-vault resolution indexes (exact path with/without .md, unique basename).
            data class Index(val byPath: Set<String>, val byBase: Map<String, List<String>>)
            val indexes = vaults.mapValues { (_, notes) ->
                val byBase = HashMap<String, MutableList<String>>()
                for (p in notes.keys) byBase.getOrPut(p.substringAfterLast('/').removeSuffix(".md").lowercase()) { mutableListOf() }.add(p)
                Index(notes.keys.toSet(), byBase)
            }

            fun resolveIn(vault: String, rawTarget: String): String? {
                val idx = indexes[vault] ?: return null
                val t = rawTarget.substringBefore('#').substringBefore('|').trim().replace('\\', '/').trimStart('/')
                if (t.isEmpty()) return null
                if (t in idx.byPath) return t
                if ("$t.md" in idx.byPath) return "$t.md"
                val m = idx.byBase[t.substringAfterLast('/').removeSuffix(".md").lowercase()]
                return if (m != null && m.size == 1) m.single() else null
            }

            val out = HashMap<String, List<Edge>>()
            val back = HashMap<String, MutableList<String>>()
            for ((vault, notes) in vaults) {
                for ((path, content) in notes) {
                    val src = "$vault:$path"
                    val edges = WikiLinks.extract(content).map { raw ->
                        // Qualified `vault:target` when the prefix is a known vault id; else local.
                        val colon = raw.indexOf(':')
                        val (tVault, tTarget) = if (colon > 0 && vaults.containsKey(raw.substring(0, colon)))
                            raw.substring(0, colon) to raw.substring(colon + 1) else vault to raw
                        val resolved = resolveIn(tVault, tTarget)?.let { "$tVault:$it" }
                        Edge(raw, resolved)
                    }
                    out[src] = edges
                    for (e in edges) e.resolvedGlobalId?.let { back.getOrPut(it) { mutableListOf() }.add(src) }
                }
            }
            return FederatedLinkGraph(out, back)
        }
    }
}
