package dev.svod.engine.graph

import dev.svod.engine.index.GitReader
import dev.svod.engine.index.MarkdownChunker
import org.eclipse.jgit.diff.DiffEntry
import java.nio.file.Path

/**
 * Per-vault link graph + tag counts maintained incrementally from git history, so `/file/links`,
 * `/graph` and `/tags` don't re-read and re-parse every note on each HEAD change.
 *
 * Each note's extracted wikilink targets and tags (the per-file local data) are cached; on a query
 * the index catches up to the current committed HEAD by applying only the DIFF since the last sync
 * (reading just the changed blobs). Link RESOLUTION (target → note) depends on the whole path set,
 * so it is recomputed in-memory from the cached targets — no file I/O, no markdown re-parse for
 * unchanged notes. A single lock serializes catch-up and queries, so a query never sees a
 * half-applied diff; the cheap HEAD check makes a no-change query effectively free.
 */
class LinkIndex(root: Path) : AutoCloseable {

    private val reader = GitReader(root)
    private val lock = Any()

    private val targets = HashMap<String, List<String>>()   // path → cleaned wikilink targets
    private val tagsByPath = HashMap<String, List<String>>() // path → tags
    private var head: String? = null
    private var initialized = false
    private var cachedGraph: LinkGraph? = null
    private var cachedTags: List<TagCount>? = null

    data class TagCount(val tag: String, val count: Int)

    fun graph(): LinkGraph = synchronized(lock) {
        syncToHead()
        cachedGraph ?: LinkGraph.buildFromTargets(targets).also { cachedGraph = it }
    }

    fun tagCounts(): List<TagCount> = synchronized(lock) {
        syncToHead()
        cachedTags ?: computeTags().also { cachedTags = it }
    }

    /** path → cleaned targets, for the federated cross-vault graph (caught up to HEAD). */
    fun targetsByPath(): Map<String, List<String>> = synchronized(lock) {
        syncToHead()
        HashMap(targets)
    }

    private fun syncToHead() {
        val h = reader.headCommit()
        if (initialized && h == head) return
        val prev = head
        if (!initialized || prev == null || h == null) { fullScan(h); return }
        val changes = try { reader.diffMarkdown(prev, h) } catch (_: Exception) { fullScan(h); return }
        for (c in changes) when (c.type) {
            DiffEntry.ChangeType.DELETE -> remove(c.path)
            DiffEntry.ChangeType.RENAME -> { remove(c.path); load(c.newPath, h) }
            else -> load(if (c.type == DiffEntry.ChangeType.COPY) c.newPath else c.path, h)
        }
        head = h
        initialized = true
        invalidate()
    }

    private fun fullScan(h: String?) {
        targets.clear(); tagsByPath.clear()
        if (h != null) for (path in reader.listMarkdown(h).keys) load(path, h)
        head = h
        initialized = true
        invalidate()
    }

    private fun load(path: String, commit: String) {
        val bytes = reader.readBlob(commit, path) ?: return remove(path)
        val content = String(bytes, Charsets.UTF_8)
        targets[path] = WikiLinks.extract(content)
        tagsByPath[path] = MarkdownChunker.parse(content).tags
    }

    private fun remove(path: String) { targets.remove(path); tagsByPath.remove(path) }

    private fun invalidate() { cachedGraph = null; cachedTags = null }

    private fun computeTags(): List<TagCount> {
        val counts = HashMap<String, Int>()
        for (ts in tagsByPath.values) for (t in ts) counts.merge(t, 1, Int::plus)
        return counts.entries.sortedByDescending { it.value }.map { TagCount(it.key, it.value) }
    }

    override fun close() = reader.close()
}
