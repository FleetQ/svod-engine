package dev.svod.engine.mcp

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.graph.LinkGraph
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.SearchQuery
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The MCP tool surface, transport-agnostic. Every call:
 *  1. is rate-limited per agent (quota),
 *  2. is authorized by role (mutations require WRITE),
 *  3. is audited (mutations always; the agent identity flows to git as the commit author),
 *  4. maps engine outcomes to a structured [ToolResult].
 *
 * This class has no knowledge of HTTP or the MCP wire format, so it is unit-testable
 * directly against a real engine.
 */
class SvodTools(
    private val engine: SvodEngine,
    private val index: IndexService,
    private val audit: AuditLog,
    private val rateLimiter: RateLimiter,
    private val eventBus: EventBus = EventBus(),
) {
    // ---- read-only tools ----

    suspend fun read(agent: AgentIdentity, path: String): ToolResult = guarded(agent, "read", write = false) {
        val f = engine.read(path) ?: return@guarded ToolResult.notFound(path)
        ToolResult.ok { put("path", f.path); put("revision", f.revision); put("content", f.text) }
    }

    suspend fun list(agent: AgentIdentity, pathPrefix: String?): ToolResult = guarded(agent, "list", write = false) {
        val paths = engine.list().let { all -> if (pathPrefix == null) all else all.filter { it.startsWith(pathPrefix) } }
        ToolResult.ok { putJsonArray("paths") { paths.forEach { add(it) } } }
    }

    suspend fun search(agent: AgentIdentity, query: SearchQuery): ToolResult = guarded(agent, "search", write = false) {
        val result = index.search(query)
        ToolResult.ok {
            put("mode", result.mode.name)
            putJsonArray("hits") {
                result.hits.forEach { h ->
                    addJsonObject {
                        put("path", h.path); put("heading", h.heading); put("snippet", h.snippet)
                        put("score", h.score); put("matchedKeyword", h.matchedKeyword); put("matchedSemantic", h.matchedSemantic)
                        putJsonArray("tags") { h.tags.forEach { add(it) } }
                    }
                }
            }
        }
    }

    suspend fun history(agent: AgentIdentity, path: String, max: Int): ToolResult = guarded(agent, "history", write = false) {
        val commits = engine.history(path, max)
        ToolResult.ok {
            putJsonArray("commits") {
                commits.forEach { c ->
                    addJsonObject {
                        put("commit", c.commit); put("author", c.authorName); put("email", c.authorEmail)
                        put("epochSeconds", c.epochSeconds); put("message", c.message)
                    }
                }
            }
        }
    }

    suspend fun diff(agent: AgentIdentity, path: String, from: String, to: String): ToolResult =
        guarded(agent, "diff", write = false) {
            val unified = try {
                engine.diff(path, from, to)
            } catch (e: IllegalArgumentException) {
                return@guarded ToolResult.badRequest(e.message ?: "invalid revision")
            }
            ToolResult.ok { put("path", path); put("from", from); put("to", to); put("diff", unified) }
        }

    suspend fun getRevision(agent: AgentIdentity, path: String, revision: String): ToolResult =
        guarded(agent, "get_revision", write = false) {
            val f = engine.getRevision(path, revision) ?: return@guarded ToolResult.notFound("$path@$revision")
            ToolResult.ok { put("path", f.path); put("revision", f.revision); put("content", f.text) }
        }

    suspend fun link(agent: AgentIdentity, path: String): ToolResult = guarded(agent, "link", write = false) {
        val g = linkGraph()
        ToolResult.ok {
            put("path", path)
            putJsonArray("outlinks") {
                g.outlinks(path).forEach { l -> addJsonObject { put("target", l.target); put("resolved", l.resolvedPath) } }
            }
            putJsonArray("unresolved") { g.unresolved(path).forEach { add(it) } }
        }
    }

    suspend fun graphQuery(agent: AgentIdentity, path: String): ToolResult = guarded(agent, "graph_query", write = false) {
        val n = linkGraph().neighborhood(path)
        ToolResult.ok {
            put("path", n.path)
            putJsonArray("outlinks") { n.outlinks.forEach { l -> addJsonObject { put("target", l.target); put("resolved", l.resolvedPath) } } }
            putJsonArray("backlinks") { n.backlinks.forEach { add(it) } }
        }
    }

    /**
     * Assemble a token-budgeted, cited context block from hybrid retrieval — the agent-memory recall
     * primitive. Runs the same hybrid search (BM25 + kNN + RRF + any reranker), then DEDUPS to one
     * block per note (so a single note can't dominate), and greedily fills up to [tokenBudget] in
     * score order. Each block carries PROVENANCE (the latest commit + author that touched the note).
     * Token cost is a char/4 heuristic — no tokenizer dependency. At least the top block is always
     * returned, even if it alone exceeds the budget. Degrades with search (keyword-only if semantic is down).
     */
    /**
     * Assemble a cited context block. Two modes:
     *  - **Path B** (default, ranked): hybrid search, dedup-per-note, token-budgeted.
     *  - **Path A** ([enumerate]=true): the "rule book" — EVERY note matching the query's filters
     *    (type/tag/prefix + the default lifecycle hiding), in full, unranked, deterministic by path,
     *    ignoring the token budget (capped at [ENUMERATE_CAP] notes for safety). Use with a `type`
     *    or `tags` filter to load all active policies/preferences verbatim every turn.
     */
    suspend fun contextPack(agent: AgentIdentity, query: SearchQuery, tokenBudget: Int, enumerate: Boolean = false): ToolResult =
        guarded(agent, "context_pack", write = false) {
            val blocks = ArrayList<PackBlock>()
            var total = 0
            val mode: String
            if (enumerate) {
                mode = "enumerate"
                val paths = index.enumerate(query.filters, ENUMERATE_CAP)
                for (p in paths) {
                    val content = engine.read(p)?.text ?: continue
                    val est = estimateTokens(content)
                    val prov = engine.history(p, 1).firstOrNull()
                    blocks.add(PackBlock(p, "", content, 0.0, prov?.commit, prov?.authorName, est))
                    total += est
                }
            } else {
                val result = index.search(query)
                mode = result.mode.name
                val seenPaths = HashSet<String>()
                for (h in result.hits) {
                    if (!seenPaths.add(h.path)) continue // one block per note (dedup + source diversity)
                    val content = engine.read(h.path)?.text ?: h.snippet
                    val est = estimateTokens(content)
                    if (blocks.isNotEmpty() && total + est > tokenBudget) continue // keep ≥1 block, else respect budget
                    val prov = engine.history(h.path, 1).firstOrNull()
                    blocks.add(PackBlock(h.path, h.heading, content, h.score, prov?.commit, prov?.authorName, est))
                    total += est
                    if (total >= tokenBudget) break
                }
            }
            ToolResult.ok {
                put("query", query.text); put("mode", mode)
                put("tokenBudget", tokenBudget); put("estimatedTokens", total)
                putJsonArray("blocks") {
                    blocks.forEach { b ->
                        addJsonObject {
                            put("path", b.path); put("heading", b.heading); put("content", b.content)
                            put("score", b.score); put("commit", b.commit); put("author", b.author); put("tokens", b.tokens)
                        }
                    }
                }
            }
        }

    private class PackBlock(
        val path: String, val heading: String, val content: String,
        val score: Double, val commit: String?, val author: String?, val tokens: Int,
    )

    /** Cheap token estimate (~4 chars/token); avoids a tokenizer dependency, consistent with chunking. */
    private fun estimateTokens(text: String): Int = Math.ceil(text.length / 4.0).toInt()

    // ---- mutating tools (WRITE role) ----

    suspend fun write(agent: AgentIdentity, path: String, content: String, expectedRevision: String?): ToolResult =
        guarded(agent, "write", write = true) {
            outcomeResult("write", agent, engine.write(path, content, expectedRevision, agent.author), path)
        }

    suspend fun delete(agent: AgentIdentity, path: String, expectedRevision: String?): ToolResult =
        guarded(agent, "delete", write = true) {
            outcomeResult("delete", agent, engine.delete(path, expectedRevision, agent.author), path)
        }

    suspend fun move(agent: AgentIdentity, from: String, to: String, expectedRevision: String?): ToolResult =
        guarded(agent, "move", write = true) {
            val moved = engine.moveWithLinks(from, to, expectedRevision, agent.author)
            val base = outcomeResult("move", agent, moved.outcome, from, to)
            if (moved.outcome is dev.svod.engine.core.WriteOutcome.Success && moved.rewrittenBacklinks.isNotEmpty()) {
                ToolResult(base.status, kotlinx.serialization.json.buildJsonObject {
                    base.data.forEach { (k, v) -> put(k, v) }
                    putJsonArray("rewrittenBacklinks") { moved.rewrittenBacklinks.forEach { add(it) } }
                }, base.isError)
            } else {
                base
            }
        }

    suspend fun promote(agent: AgentIdentity, from: String, to: String, expectedRevision: String?): ToolResult =
        guarded(agent, "promote", write = true) {
            try {
                outcomeResult("promote", agent, engine.promote(from, to, expectedRevision, agent.author), from, to)
            } catch (e: IllegalArgumentException) {
                audit.record(agent.agentId, "promote", "bad_request", from, to, detail = e.message)
                ToolResult.badRequest(e.message ?: "invalid promotion")
            }
        }

    /**
     * Promotion gate: turn an observation into a durable, typed memory note. Mirrors the
     * classify→dedup→verify→write flow: scope is the vault; dedup is by normalized content hash
     * (identical content+type ⇒ no second note); status defaults by type (fact/policy enter
     * `provisional` — kept out of recall until confirmed — preference/episode/note enter `active`);
     * a `supersedes` target is revoked and linked. Writes through the engine (secret-scanned,
     * committed, indexed, conflict-guarded). Keeps an agent-written KB from poisoning itself.
     */
    suspend fun remember(
        agent: AgentIdentity, content: String, type: String?, subject: String?, confidence: Double?,
        source: String?, status: String?, into: String?, supersedes: String?,
    ): ToolResult = guarded(agent, "remember", write = true) {
        if (content.isBlank()) return@guarded ToolResult.badRequest("content must not be blank")
        val t = type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "fact"
        val st = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: defaultStatusFor(t)
        val dir = (into ?: "memory").trim('/').ifEmpty { "memory" }
        val hash = dev.svod.engine.index.sha256Hex("$t\n${content.trim()}").take(12)
        val path = "$dir/$t/$hash.md"

        // Dedup: same content+type already stored (same deterministic path, same body) ⇒ no-op write.
        val existing = engine.read(path)
        if (existing != null && bodyOf(existing.text).trim() == content.trim()) {
            audit.record(agent.agentId, "remember", "deduped", path)
            return@guarded ToolResult.ok { put("status", "deduped"); put("path", path); put("type", t); put("memoryStatus", existing.let { statusOf(it.text) ?: st }) }
        }

        // Supersession: revoke the prior memory and link it forward (a normal conflict-guarded write).
        var superseded: String? = null
        if (!supersedes.isNullOrBlank()) {
            engine.read(supersedes)?.let { old ->
                val o = engine.write(supersedes, revoke(old.text, path), old.revision, agent.author)
                if (o is WriteOutcome.Success) superseded = supersedes
            }
        }

        val note = buildMemoryNote(t, st, subject, confidence, source, content.trim())
        when (val outcome = engine.write(path, note, existing?.revision, agent.author)) {
            is WriteOutcome.Success -> {
                audit.record(agent.agentId, "remember", "ok", path, revision = outcome.revision, detail = outcome.commit)
                eventBus.publish(EventTypes.AGENT_ACTIVITY) { put("agentId", agent.agentId); put("tool", "remember"); put("path", path); put("commit", outcome.commit) }
                eventBus.publish(EventTypes.COMMIT_CREATED) { put("commit", outcome.commit); put("path", path); put("author", agent.author.name) }
                ToolResult.ok {
                    put("status", "written"); put("path", path); put("type", t); put("memoryStatus", st)
                    put("revision", outcome.revision); put("commit", outcome.commit)
                    superseded?.let { put("superseded", it) }
                }
            }
            else -> outcomeResult("remember", agent, outcome, path)
        }
    }

    private fun defaultStatusFor(type: String): String = when (type) {
        "fact", "policy" -> "provisional" // durable but kept out of recall until confirmed
        else -> "active"                  // preference / episode / note
    }

    /** Strip a leading YAML frontmatter block, returning the body. */
    private fun bodyOf(text: String): String =
        dev.svod.engine.index.MarkdownChunker.parse(text).body

    private fun statusOf(text: String): String? =
        dev.svod.engine.index.MarkdownChunker.parse(text).status

    /** Re-serialize [oldText] with status=revoked + superseded_by=[newPath], preserving its body. */
    private fun revoke(oldText: String, newPath: String): String {
        val parsed = dev.svod.engine.index.MarkdownChunker.parse(oldText)
        val fm = LinkedHashMap<String, Any?>(parsed.frontmatter)
        fm["status"] = "revoked"
        fm["superseded_by"] = newPath
        return frontmatterFences(fm) + parsed.body.trimStart('\n')
    }

    private fun buildMemoryNote(type: String, status: String, subject: String?, confidence: Double?, source: String?, content: String): String {
        val fm = LinkedHashMap<String, Any?>()
        fm["type"] = type
        fm["status"] = status
        subject?.takeIf { it.isNotBlank() }?.let { fm["subject"] = it }
        confidence?.let { fm["confidence"] = it }
        source?.takeIf { it.isNotBlank() }?.let { fm["source"] = it }
        fm["created"] = java.time.Instant.now().toString()
        return frontmatterFences(fm) + content + "\n"
    }

    private fun frontmatterFences(fm: Map<String, Any?>): String {
        val opts = org.yaml.snakeyaml.DumperOptions().apply {
            defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
        }
        val yaml = org.yaml.snakeyaml.Yaml(opts).dump(fm).trimEnd('\n')
        return "---\n$yaml\n---\n"
    }

    // ---- internals ----

    /** Rate-limit + role gate shared by every tool. Returns the guard failure, else runs [body]. */
    private suspend inline fun guarded(
        agent: AgentIdentity,
        tool: String,
        write: Boolean,
        body: () -> ToolResult,
    ): ToolResult {
        if (write && !agent.role.canWrite) {
            audit.record(agent.agentId, tool, "denied", detail = "role ${agent.role}")
            return ToolResult.denied("agent '${agent.agentId}' (role ${agent.role}) may not call '$tool'")
        }
        if (!rateLimiter.tryAcquire(agent.agentId)) {
            audit.record(agent.agentId, tool, "rate_limited")
            return ToolResult.rateLimited("agent '${agent.agentId}' exceeded its rate limit")
        }
        return body()
    }

    private fun outcomeResult(tool: String, agent: AgentIdentity, outcome: WriteOutcome, path: String, target: String? = null): ToolResult =
        when (outcome) {
            is WriteOutcome.Success -> {
                audit.record(agent.agentId, tool, "ok", path, target, outcome.revision, outcome.commit)
                eventBus.publish(EventTypes.AGENT_ACTIVITY) {
                    put("agentId", agent.agentId); put("tool", tool); put("path", outcome.path); put("commit", outcome.commit)
                }
                eventBus.publish(EventTypes.COMMIT_CREATED) {
                    put("commit", outcome.commit); put("path", outcome.path); put("author", agent.author.name)
                }
                ToolResult.ok { put("path", outcome.path); put("revision", outcome.revision); put("commit", outcome.commit) }
            }
            is WriteOutcome.Conflict -> {
                audit.record(agent.agentId, tool, "conflict", path, target, outcome.current)
                eventBus.publish(EventTypes.CONFLICT) { put("path", outcome.path); put("agentId", agent.agentId); put("tool", tool) }
                ToolResult.conflict {
                    put("path", outcome.path); put("expected", outcome.expected)
                    put("current", outcome.current); put("currentContent", outcome.currentContent)
                }
            }
            is WriteOutcome.NotFound -> {
                audit.record(agent.agentId, tool, "not_found", path, target)
                ToolResult.notFound(outcome.path)
            }
            is WriteOutcome.Blocked -> {
                audit.record(agent.agentId, tool, "blocked", path, target, detail = outcome.findings.joinToString(", "))
                ToolResult.blocked(outcome.path, outcome.findings)
            }
        }

    // Link graph cached by HEAD; rebuilt only when the vault advances.
    private val graphLock = Any()
    private var cachedHead: String? = null
    private var cachedGraph: LinkGraph? = null

    private suspend fun linkGraph(): LinkGraph {
        val head = engine.head()
        synchronized(graphLock) {
            val g = cachedGraph
            if (g != null && cachedHead == head) return g
        }
        val notes = engine.list().associateWith { (engine.read(it)?.text ?: "") }
        val graph = LinkGraph.build(notes)
        synchronized(graphLock) { cachedHead = head; cachedGraph = graph }
        return graph
    }

    private companion object {
        /** Safety cap on Path-A enumeration (logged via the result size if hit); avoids pathological packs. */
        const val ENUMERATE_CAP = 500
    }
}
