package dev.svod.engine.sync

import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.merge.MergeAlgorithm
import org.eclipse.jgit.merge.MergeFormatter
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/**
 * A structured, frontmatter-aware 3-way merge for markdown notes.
 *
 * - **YAML frontmatter** is merged at the key level (not line by line): a key changed on only
 *   one side takes that side; both sides to the same value is fine; tag-style lists union;
 *   a scalar changed differently on both sides is a real conflict.
 * - **The body** is merged with git's own 3-way line algorithm (jgit `MergeAlgorithm`).
 *
 * Conflicts are *surfaced*, never auto-resolved: a `Conflict` carries base/ours/theirs so a
 * 3-way merge UI (or an agent) can resolve it. Clean merges are deterministic.
 */
object FrontmatterMerge {

    sealed interface Outcome {
        data class Merged(val content: String) : Outcome
        data class Conflict(val reasons: List<String>, val base: String, val ours: String, val theirs: String) : Outcome
    }

    fun merge(base: String?, ours: String, theirs: String): Outcome {
        val b = Doc.parse(base ?: "")
        val o = Doc.parse(ours)
        val t = Doc.parse(theirs)

        val reasons = mutableListOf<String>()
        val mergedFm = mergeFrontmatter(b.fm, o.fm, t.fm, reasons)
        val mergedBody = mergeBody(b.body, o.body, t.body, reasons)

        if (reasons.isNotEmpty() || mergedBody == null) {
            return Outcome.Conflict(reasons.ifEmpty { listOf("body conflict") }, base ?: "", ours, theirs)
        }
        val head = if (mergedFm == null || mergedFm.isEmpty()) "" else serializeFrontmatter(mergedFm)
        return Outcome.Merged(head + mergedBody)
    }

    // ---- frontmatter (key-level) ----

    private fun mergeFrontmatter(
        base: Map<String, Any?>?,
        ours: Map<String, Any?>?,
        theirs: Map<String, Any?>?,
        reasons: MutableList<String>,
    ): Map<String, Any?>? {
        if (base == null && ours == null && theirs == null) return null
        val b = base ?: emptyMap()
        val o = ours ?: emptyMap()
        val t = theirs ?: emptyMap()

        val absent = Any() // sentinel for "key not present"
        val merged = sortedMapOf<String, Any?>()
        for (key in (o.keys + t.keys + b.keys).toSortedSet()) {
            val bv = if (b.containsKey(key)) b[key] else absent
            val ov = if (o.containsKey(key)) o[key] else absent
            val tv = if (t.containsKey(key)) t[key] else absent

            val chosen: Any? = when {
                eq(ov, tv) -> ov                 // both sides agree (incl. both removed)
                eq(ov, bv) -> tv                 // ours unchanged → take theirs
                eq(tv, bv) -> ov                 // theirs unchanged → take ours
                ov is List<*> && tv is List<*> -> unionLists(ov, tv) // both changed lists → union
                else -> { reasons += "frontmatter key '$key' changed on both sides"; absent }
            }
            if (chosen !== absent) merged[key] = chosen
        }
        return merged
    }

    private fun eq(a: Any?, b: Any?): Boolean = a == b

    private fun unionLists(a: List<*>, b: List<*>): List<Any?> {
        val out = LinkedHashSet<Any?>()
        out.addAll(a); out.addAll(b)
        return out.toList()
    }

    private fun serializeFrontmatter(fm: Map<String, Any?>): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            isAllowUnicode = true // emit Cyrillic/UTF-8 literally, not \uXXXX
        }
        val yaml = Yaml(options).dump(fm).trimEnd('\n')
        return "---\n$yaml\n---\n"
    }

    // ---- body (line-level, git's algorithm) ----

    private fun mergeBody(base: String, ours: String, theirs: String, reasons: MutableList<String>): String? {
        val result = MergeAlgorithm().merge(
            RawTextComparator.DEFAULT,
            RawText(base.toByteArray(UTF_8)),
            RawText(ours.toByteArray(UTF_8)),
            RawText(theirs.toByteArray(UTF_8)),
        )
        if (result.containsConflicts()) {
            reasons += "body has conflicting line edits"
            return null
        }
        val out = ByteArrayOutputStream()
        MergeFormatter().formatMerge(out, result, "base", "ours", "theirs", UTF_8)
        return out.toString(UTF_8)
    }

    /** A parsed note: optional frontmatter map + body. */
    private data class Doc(val fm: Map<String, Any?>?, val body: String) {
        companion object {
            private val FENCE = Regex("^\\uFEFF?---\\r?\\n(.*?)\\r?\\n---\\r?\\n?", RegexOption.DOT_MATCHES_ALL)

            @Suppress("UNCHECKED_CAST")
            fun parse(text: String): Doc {
                val m = FENCE.find(text) ?: return Doc(null, text)
                val fm = try {
                    (Yaml().load(m.groupValues[1]) as? Map<String, Any?>) ?: emptyMap()
                } catch (_: Exception) {
                    return Doc(null, text) // malformed frontmatter → treat whole thing as body
                }
                return Doc(fm, text.substring(m.range.last + 1))
            }
        }
    }
}
