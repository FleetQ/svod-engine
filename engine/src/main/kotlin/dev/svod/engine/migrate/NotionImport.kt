package dev.svod.engine.migrate

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Imports an unzipped Notion "Markdown & CSV" export into Svod. It is [ObsidianImport]'s faithful
 * copy (same walk, same batch writes, same idempotency and no-clobber rules) with three additions:
 *
 * - the 32-hex Notion page id is removed from every file and folder name (`Team Wiki 0123….md` →
 *   `Team Wiki.md`), except where two siblings would then share a name (compared
 *   case-insensitively): those keep their original names;
 * - relative markdown links to other exported pages (percent-encoded, possibly `../`) become
 *   `[[vault/path|text]]` wikilinks; a `#fragment` is dropped;
 * - links to exported attachments keep their markdown form but point at the renamed paths.
 *
 * Database CSV files are not imported; each is listed in `skipped` under its renamed path. External
 * links, in-page anchors and links to anything not in the export are copied verbatim. The output
 * depends only on the export, so a re-import reports `unchanged`.
 */
object NotionImport {

    /** A Notion id at the end of a name, before an optional extension. */
    private val ID = Regex("\\s+[0-9a-f]{32}(?=(\\.[^./]+)?$)")

    /** An inline markdown link or image; the target may hold one level of balanced parentheses. */
    private val LINK = Regex("(!?)\\[([^\\[\\]]*)]\\(((?:[^()\\s]|\\([^()\\s]*\\))+)\\)")

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** A path with `#`, `|`, `[` or `]` cannot be written as a wikilink target that resolves back to it. */
    private const val UNLINKABLE = "#|[]"

    suspend fun import(
        source: Path,
        engine: SvodEngine,
        author: Author = Author("notion-import", "import@svod.localhost"),
        into: String = "notion",
        followSymlinks: Boolean = false,
    ): ObsidianImport.Result {
        val base = source.normalize()
        val prefix = ObsidianImport.prefixOf(into)
        val walk = ObsidianImport.collectFiles(base, prefix, followSymlinks)

        // Pass 1: the renamed vault path of every walked file (CSV files included, for `skipped`).
        val newRel = renamed(base, prefix, walk.files)
        val (csv, files) = walk.files.partition { it.fileName.toString().lowercase().endsWith(".csv") }
        val byRel = files.associateBy { newRel.getValue(it) }
        val byPath = files.associateBy { it.normalize() }
        val skipped = walk.skipped + csv.map { newRel.getValue(it) }

        // Pass 2: the shared import with renamed paths and the link rewrite.
        return ObsidianImport.importWith(
            source, engine, author, into, followSymlinks,
            walk = ObsidianImport.Walk(files, skipped),
            pathOf = { newRel.getValue(it) },
        ) { rel, text ->
            val file = byRel[rel] ?: return@importWith text
            LINK.replace(text) { m -> rewrite(m, file, rel, byPath, newRel) ?: m.value }
        }
    }

    /** The rewritten link for [m] in the note [file] (written at [rel]), or null to keep it as is. */
    private fun rewrite(m: MatchResult, file: Path, rel: String, byPath: Map<Path, Path>, newRel: Map<Path, String>): String? {
        val (bang, text, target) = m.destructured
        if (target.startsWith("#") || SCHEME.containsMatchIn(target)) return null
        val decoded = try {
            URLDecoder.decode(target.replace("+", "%2B"), UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }.substringBefore('#')
        if (decoded.isEmpty()) return null
        val resolved = try {
            file.parent.resolve(decoded).normalize()
        } catch (e: InvalidPathException) {
            return null
        }
        val hit = byPath[resolved] ?: return null
        val hitRel = newRel.getValue(hit)
        return if (hitRel.endsWith(".md")) {
            if (bang.isNotEmpty() || hitRel.any { it in UNLINKABLE }) return null
            val path = hitRel.removeSuffix(".md")
            if (text.isBlank()) "[[$path]]" else "[[$path|$text]]"
        } else {
            val from = Paths.get(rel).parent ?: Paths.get("")
            val relative = from.relativize(Paths.get(hitRel)).toString().replace('\\', '/')
            "$bang[$text](${relative.split('/').joinToString("/") { encode(it) }})"
        }
    }

    /** Percent-encodes the characters that would break or change a markdown link target. */
    private fun encode(segment: String): String =
        segment.replace("%", "%25").replace(" ", "%20").replace("(", "%28").replace(")", "%29")

    /**
     * Each file's vault path with the Notion ids removed from every segment. Within one original
     * parent, segments whose cleaned names collide (case-insensitively) all keep their original
     * names, so the result does not depend on walk order.
     */
    private fun renamed(base: Path, prefix: String, files: List<Path>): Map<Path, String> {
        val segments = files.associateWith { f -> base.relativize(f).map { it.toString() } }
        // (original parent segments) → cleaned lowercase name → original segments with that name
        val siblings = HashMap<List<String>, HashMap<String, MutableSet<String>>>()
        for (segs in segments.values) {
            for (i in segs.indices) {
                siblings.getOrPut(segs.subList(0, i)) { HashMap() }
                    .getOrPut(clean(segs[i]).lowercase()) { HashSet() }
                    .add(segs[i])
            }
        }
        return segments.mapValues { (_, segs) ->
            prefix + segs.indices.joinToString("/") { i ->
                val cleaned = clean(segs[i])
                if (siblings.getValue(segs.subList(0, i)).getValue(cleaned.lowercase()).size > 1) segs[i] else cleaned
            }
        }
    }

    private fun clean(segment: String): String {
        val cleaned = ID.replace(segment, "")
        // Never turn a name into an empty or hidden one.
        return if (cleaned.isBlank() || cleaned.startsWith(".")) segment else cleaned
    }
}
