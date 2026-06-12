package dev.svod.engine.mcp

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
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
            outcomeResult("move", agent, engine.move(from, to, expectedRevision, agent.author), from, to)
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
                ToolResult.ok { put("path", outcome.path); put("revision", outcome.revision); put("commit", outcome.commit) }
            }
            is WriteOutcome.Conflict -> {
                audit.record(agent.agentId, tool, "conflict", path, target, outcome.current)
                ToolResult.conflict {
                    put("path", outcome.path); put("expected", outcome.expected)
                    put("current", outcome.current); put("currentContent", outcome.currentContent)
                }
            }
            is WriteOutcome.NotFound -> {
                audit.record(agent.agentId, tool, "not_found", path, target)
                ToolResult.notFound(outcome.path)
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
}
