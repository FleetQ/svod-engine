package dev.svod.engine.mcp

import dev.svod.engine.core.GuardedWrite
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.graph.LinkGraph
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.SearchQuery
import dev.svod.engine.memory.Classification
import dev.svod.engine.memory.ClassificationPlan
import dev.svod.engine.memory.FactClassifier
import dev.svod.engine.memory.MemoryAdjudicator
import dev.svod.engine.memory.MemoryCandidate
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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
    /**
     * Optional LLM adjudicator for `remember`'s ambiguous classification band. Null (the default)
     * keeps the engine LLM-free: ambiguous cases are reported UNCERTAIN instead of guessed.
     */
    adjudicator: MemoryAdjudicator? = null,
    /**
     * The vault's derived thematic graph, when it has one. Null keeps every graph tool answering
     * "not built" instead of failing — the tools stay registered so an agent's capability probe does
     * not change shape depending on config.
     */
    private val graph: dev.svod.engine.graphrag.GraphService? = null,
    /**
     * The vault these tools are bound to, stamped onto every event they publish (`data.vault`).
     * The App API has always tagged its commits; without the tag on MCP writes, per-vault
     * listeners (backup/sync on-change, the app's review inbox and per-vault filters) either
     * skip the event or guess the vault. Null only in tests that build tools without a vault.
     */
    private val vaultId: String? = null,
) {
    /** Classifies an incoming memory against existing memory of the same type/subject. */
    private val classifier = FactClassifier(engine, index, adjudicator)

    private fun JsonObjectBuilder.putVault() { vaultId?.let { put("vault", it) } }

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
     * The vault's thematic map (Ниво 2), deliberately WITHOUT the full member lists.
     *
     * **No model is consulted here.** With a query the ranking uses the same embedder the search path
     * already uses; the summaries themselves were written at build time. The caller receives evidence
     * and does its own reasoning — that is what keeps query time LLM-free (test D1).
     *
     * Measured on a 3,096-note vault: emitting every member path made one default call ≈44,300
     * tokens, of which **97% were paths** and only ~1,200 were the titles and summaries an agent
     * actually reasons over. A tool that costs more context than it returns signal is one an agent
     * is right to avoid, so the bulk call now carries a small [dev.svod.engine.graphrag.MEMBER_SAMPLE] preview and the caller
     * fetches the full list for the ONE community it cares about via [graphCommunity].
     */
    suspend fun graphCommunities(agent: AgentIdentity, query: String?, level: Int?, limit: Int): ToolResult =
        guarded(agent, "graph_communities", write = false) {
            val g = graph ?: return@guarded ToolResult.ok {
                put("state", "NOT_BUILT")
                put("hint", "the thematic graph has not been built for this vault; that is not the same as the vault having no themes")
                putJsonArray("communities") {}
            }
            val status = g.status()
            val found = g.communities(query, level, limit)
            ToolResult.ok {
                put("state", status.state)
                put("stale", status.stale)
                put("levelCount", status.levelCount)
                // The level actually served, so a caller can ask for a finer one deliberately.
                put("level", found.firstOrNull()?.level)
                if (status.state == "NOT_BUILT") {
                    put("hint", "not built yet — ask the user to run a graph rebuild; do not conclude the vault has no themes")
                }
                if (status.stale) {
                    put("hint", "built against an older commit: the themes are still valid but may miss the most recent notes")
                }
                if (status.incremental) {
                    // Attached notes ARE reachable through these themes; pending ones are on no theme
                    // at all — a materially different thing to tell an agent than one "stale" flag.
                    put("attachedSinceBuild", status.attachedCount)
                    put("notOnAnyTheme", status.pendingCount)
                }
                putJsonArray("communities") {
                    found.forEach { c ->
                        addJsonObject {
                            put("id", c.id); put("level", c.level); put("title", c.title)
                            put("summary", c.summary); put("size", c.size)
                            // A preview only. `size` is the true count; call graph_community for all.
                            putJsonArray("sampleMembers") { c.members.take(dev.svod.engine.graphrag.MEMBER_SAMPLE).forEach { add(it) } }
                            if (c.size > dev.svod.engine.graphrag.MEMBER_SAMPLE) put("moreMembers", c.size - dev.svod.engine.graphrag.MEMBER_SAMPLE)
                        }
                    }
                }
            }
        }

    /** Every member path of ONE community — the targeted follow-up to [graphCommunities]. */
    suspend fun graphCommunity(agent: AgentIdentity, id: String): ToolResult =
        guarded(agent, "graph_community", write = false) {
            val c = graph?.community(id)
                ?: return@guarded ToolResult.notFound("community $id")
            ToolResult.ok {
                put("id", c.id); put("level", c.level); put("title", c.title)
                put("summary", c.summary); put("size", c.size)
                putJsonArray("members") { c.members.forEach { add(it) } }
            }
        }

    /** Build state of the derived graph, including whether it is stale relative to HEAD. */
    suspend fun graphStatus(agent: AgentIdentity): ToolResult = guarded(agent, "graph_status", write = false) {
        val s = graph?.status()
            ?: dev.svod.engine.graphrag.GraphStatus(state = "NOT_BUILT", enabled = false)
        ToolResult.ok {
            put("state", s.state); put("enabled", s.enabled); put("stale", s.stale)
            put("head", s.head); put("currentHead", s.currentHead); put("builtAt", s.builtAt)
            put("noteCount", s.noteCount); put("edgeCount", s.edgeCount)
            put("linkEdgeCount", s.linkEdgeCount); put("simEdgeCount", s.simEdgeCount)
            put("communityCount", s.communityCount); put("levelCount", s.levelCount)
            put("vectorCoverage", s.vectorCoverage); put("summaryProvider", s.summaryProvider)
            put("summarisedCount", s.summarisedCount); put("error", s.error); put("progress", s.progress)
            // Incremental attachment (0.26.0). `attachedCount`/`pendingCount` mean nothing unless
            // `incremental` is true — with it off they are simply never computed.
            put("incremental", s.incremental)
            put("attachedCount", s.attachedCount); put("pendingCount", s.pendingCount)
            // 0.27.0. A proxy: sampled attachments whose placement vote has since changed. 0.0 means
            // none of the sample drifted, NOT that the partition is still what Louvain would build.
            put("driftRatio", s.driftRatio)
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
    suspend fun contextPack(
        agent: AgentIdentity,
        query: SearchQuery,
        tokenBudget: Int,
        enumerate: Boolean = false,
        graphExpand: Boolean = false,
    ): ToolResult =
        guarded(agent, "context_pack", write = false) {
            val blocks = ArrayList<PackBlock>()
            var total = 0
            val mode: String
            if (enumerate) {
                mode = "enumerate"
                val paths = index.enumerate(query.filters, ENUMERATE_CAP)
                for (p in paths) {
                    // Re-read is the full note, so strip `<private>` spans here too (index-only stripping
                    // wouldn't cover this path). `private: true` notes never reach enumerate (not indexed).
                    val content = engine.read(p)?.text?.let(dev.svod.engine.index.MarkdownChunker::stripPrivateSpans) ?: continue
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
                    // Full-note re-read ⇒ strip `<private>` spans (the index snippet is already stripped).
                    val content = engine.read(h.path)?.text?.let(dev.svod.engine.index.MarkdownChunker::stripPrivateSpans) ?: h.snippet
                    val est = estimateTokens(content)
                    if (blocks.isNotEmpty() && total + est > tokenBudget) continue // keep ≥1 block, else respect budget
                    val prov = engine.history(h.path, 1).firstOrNull()
                    blocks.add(PackBlock(h.path, h.heading, content, h.score, prov?.commit, prov?.authorName, est))
                    total += est
                    if (total >= tokenBudget) break
                }
                if (graphExpand && total < tokenBudget) {
                    // Contained: a link-graph failure must leave the primary blocks exactly as they
                    // were (test E5). Expansion is a bonus, never a precondition.
                    total = runCatching { expandViaGraph(blocks, total, tokenBudget) }.getOrDefault(total)
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
                            // Additive and only present on expanded blocks, so an agent can tell
                            // primary evidence from the context pulled in around it.
                            if (b.viaPath != null) { put("viaGraph", true); put("viaPath", b.viaPath) }
                        }
                    }
                }
            }
        }

    /**
     * Ниво 1 recall expansion: pull the 1-hop wikilink neighbourhood of the strongest hits into the
     * remaining token budget.
     *
     * Order matters — neighbours are appended AFTER the ranked blocks and never displace one
     * (test E3), because a linked note is context for the answer, not the answer. Notes already
     * packed are skipped (test E4). Returns the new running token total.
     */
    private suspend fun expandViaGraph(blocks: MutableList<PackBlock>, startTotal: Int, tokenBudget: Int): Int {
        if (blocks.isEmpty()) return startTotal
        val g = linkGraph()
        val packed = blocks.mapTo(HashSet()) { it.path }
        var total = startTotal

        // Only the strongest few hits get expanded; every hit's neighbourhood would swamp the budget
        // with material the query never actually matched.
        val seeds = blocks.take(EXPAND_SEEDS).map { it.path }
        val candidates = LinkedHashMap<String, String>() // neighbour path -> the hit that pulled it in
        for (seed in seeds) {
            for (l in g.outlinks(seed)) {
                val target = l.resolvedPath ?: continue
                if (target !in packed) candidates.putIfAbsent(target, seed)
            }
            for (b in g.backlinks(seed)) if (b !in packed) candidates.putIfAbsent(b, seed)
        }

        for ((path, via) in candidates) {
            if (total >= tokenBudget) break
            val content = engine.read(path)?.text?.let(dev.svod.engine.index.MarkdownChunker::stripPrivateSpans) ?: continue
            val est = estimateTokens(content)
            if (total + est > tokenBudget) continue
            val prov = engine.history(path, 1).firstOrNull()
            blocks.add(PackBlock(path, "", content, 0.0, prov?.commit, prov?.authorName, est, viaPath = via))
            total += est
        }
        return total
    }

    private class PackBlock(
        val path: String, val heading: String, val content: String,
        val score: Double, val commit: String?, val author: String?, val tokens: Int,
        /** Non-null on a graph-expanded block: the ranked hit whose neighbourhood pulled it in. */
        val viaPath: String? = null,
    )

    /** Cheap token estimate (~4 chars/token); the App API search DTO shares the same estimator. */
    private fun estimateTokens(text: String): Int = dev.svod.engine.index.estimateTokens(text)

    // ---- mutating tools (WRITE role) ----

    suspend fun write(agent: AgentIdentity, path: String, content: String, expectedRevision: String?): ToolResult =
        guarded(agent, "write", write = true) {
            outcomeResult("write", agent, engine.write(path, content, expectedRevision, agent.author), path)
        }

    /**
     * Partial edit: replace an exact substring in place of resending the whole note (large
     * notes make verbatim rewrites transcription-risky). `oldString` must occur EXACTLY once
     * unless [replaceAll] — absence or ambiguity is a bad_request, never a guess. Concurrency:
     * the write validates against [expectedRevision] when given, else against the revision
     * read here — a concurrent writer in between still surfaces as a conflict, not a clobber.
     */
    suspend fun edit(
        agent: AgentIdentity,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
        expectedRevision: String?,
    ): ToolResult = guarded(agent, "edit", write = true) {
        if (oldString.isEmpty()) return@guarded ToolResult.badRequest("oldString must be non-empty")
        if (oldString == newString) return@guarded ToolResult.badRequest("oldString and newString are identical")
        val cur = engine.read(path) ?: return@guarded ToolResult.notFound(path)
        if (expectedRevision != null && expectedRevision != cur.revision) {
            // Surface the standard conflict shape (expected/current/currentContent) via the
            // engine's own guard rather than re-implementing it here.
            return@guarded outcomeResult("edit", agent, engine.write(path, cur.text, expectedRevision, agent.author), path)
        }
        val count = countOccurrences(cur.text, oldString)
        return@guarded when {
            count == 0 ->
                ToolResult.badRequest("oldString not found in $path — re-read the note; its content may have changed")
            count > 1 && !replaceAll ->
                ToolResult.badRequest("oldString occurs $count times in $path — add surrounding context to make it unique, or pass replaceAll=true")
            else -> {
                val newContent = cur.text.replace(oldString, newString)
                // Defense-in-depth: never commit a note the transform silently corrupted (the
                // reported failure mode: matched region removed, newString never inserted, headings
                // vanished). Verify the to-be-written content BEFORE the write and refuse it on any
                // deviation — strictly better than catching it post-commit.
                val integrityError = editIntegrityError(cur.text, oldString, newString, count, newContent, path)
                if (integrityError != null) {
                    audit.record(agent.agentId, "edit", "internal_error", path, detail = integrityError)
                    ToolResult.internalError(integrityError)
                } else {
                    outcomeResult("edit", agent, engine.write(path, newContent, cur.revision, agent.author), path)
                }
            }
        }
    }

    /**
     * Post-transform integrity check for [edit]. A literal replace of exactly [count] non-overlapping
     * occurrences of [oldString] with [newString] in [base] has an EXACT resulting length; any
     * deviation means content was dropped or duplicated, and a non-empty replacement must appear in
     * the [result]. Returns a human error string when [result] fails either invariant, else null.
     * Pure and internal so the guard is unit-testable with a deliberately-corrupt [result].
     */
    internal fun editIntegrityError(
        base: String,
        oldString: String,
        newString: String,
        count: Int,
        result: String,
        path: String = "note",
    ): String? {
        val expectedLen = base.length + count * (newString.length - oldString.length)
        return when {
            result.length != expectedLen ->
                "post-edit integrity check failed for $path: expected length $expectedLen " +
                    "(base ${base.length}, $count×${oldString.length}→${newString.length}), got ${result.length} — write refused"
            newString.isNotEmpty() && !result.contains(newString) ->
                "post-edit integrity check failed for $path: newString not present in result — write refused"
            else -> null
        }
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var idx = 0
        var n = 0
        while (true) {
            idx = text.indexOf(needle, idx)
            if (idx < 0) return n
            n++
            idx += needle.length
        }
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
     *
     * Before persisting, the incoming memory is CLASSIFIED against existing memory of the same type
     * and subject ([FactClassifier]): NEW is written as before, DUPLICATE is a no-op, UPDATE revokes
     * and links its predecessor, CONTRADICTION persists BOTH sides with a `contradicts:` link rather
     * than overwriting either, and UNCERTAIN is persisted with `needs-review: true`. Classification
     * is planned off the write-actor (it may embed and may call an LLM) and applied inside it, so
     * the decision is only committed if the state it was computed against has not moved.
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

        // Secret-scan BEFORE classification. The engine scans again on write, but classification may
        // embed the content with a REMOTE provider or hand it to an LLM — content that must never
        // leave the machine has to be refused before it can be sent anywhere.
        val secrets = engine.scanSecrets(content)
        if (secrets.isNotEmpty()) {
            audit.record(agent.agentId, "remember", "blocked", path, detail = secrets.joinToString(", "))
            return@guarded ToolResult.blocked(path, secrets)
        }

        // Plan off the actor, apply inside it. If a candidate moved in between, the apply is refused
        // as stale and we re-plan against live state exactly once rather than committing a decision
        // derived from state that no longer exists.
        for (attempt in 0..1) {
            val existing = engine.read(path)

            // Same content+type ⇒ same deterministic path: an exact restatement is a no-op write.
            if (existing != null && bodyOf(existing.text).trim() == content.trim()) {
                audit.record(agent.agentId, "remember", "deduped", path)
                return@guarded ToolResult.ok {
                    put("status", "deduped"); put("path", path); put("type", t)
                    put("memoryStatus", statusOf(existing.text) ?: st)
                    put("classification", Classification.DUPLICATE.name)
                    put("relatedNote", path); put("confidence", 1.0)
                }
            }

            val plan = if (!supersedes.isNullOrBlank()) {
                // The caller already declared the relationship; inferring one would only second-guess it.
                val old = engine.read(supersedes)
                    ?: return@guarded ToolResult.notFound(supersedes)
                ClassificationPlan(
                    Classification.UPDATE,
                    MemoryCandidate(supersedes, old.revision, bodyOf(old.text).trim(), subject),
                    1.0, "caller-declared supersedes", mapOf(supersedes to old.revision),
                )
            } else {
                classifier.plan(content, t, subject, path)
            }

            if (plan.classification == Classification.DUPLICATE && plan.related != null) {
                val dup = plan.related
                val dupStatus = engine.read(dup.path)?.let { statusOf(it.text) } ?: st
                audit.record(agent.agentId, "remember", "deduped", dup.path)
                return@guarded ToolResult.ok {
                    put("status", "deduped"); put("path", dup.path); put("type", t)
                    put("memoryStatus", dupStatus)
                    put("classification", Classification.DUPLICATE.name)
                    put("relatedNote", dup.path); put("confidence", plan.confidence)
                    put("rationale", plan.rationale)
                }
            }

            val related = plan.related
            val note = buildMemoryNote(
                t, st, subject, confidence, source, content.trim(),
                contradicts = related?.path?.takeIf { plan.classification == Classification.CONTRADICTION },
                supersedes = related?.path?.takeIf { plan.classification == Classification.UPDATE },
                needsReview = plan.classification == Classification.UNCERTAIN,
            )
            val files = LinkedHashMap<String, String>()
            files[path] = note
            // UPDATE keeps the predecessor's history: it is revoked and linked forward, never removed.
            if (plan.classification == Classification.UPDATE && related != null) {
                engine.read(related.path)?.let { files[related.path] = revoke(it.text, path) }
            }
            val guards = LinkedHashMap<String, String?>()
            plan.guards.forEach { (p, rev) -> guards[p] = rev }
            guards[path] = existing?.revision

            when (val outcome = engine.writeGuarded(files, guards, agent.author, "remember: $path (${plan.classification})")) {
                is GuardedWrite.Applied -> {
                    audit.record(agent.agentId, "remember", "ok", path, revision = outcome.revisions[path], detail = outcome.commit)
                    eventBus.publish(EventTypes.AGENT_ACTIVITY) { put("agentId", agent.agentId); put("tool", "remember"); put("path", path); put("commit", outcome.commit); putVault() }
                    eventBus.publish(EventTypes.COMMIT_CREATED) { put("commit", outcome.commit); put("path", path); put("author", agent.author.name); put("agentId", agent.agentId); putVault() }
                    return@guarded ToolResult.ok {
                        put("status", "written"); put("path", path); put("type", t); put("memoryStatus", st)
                        put("revision", outcome.revisions[path]); put("commit", outcome.commit)
                        put("classification", plan.classification.name)
                        put("confidence", plan.confidence); put("rationale", plan.rationale)
                        related?.let { put("relatedNote", it.path) }
                        if (plan.classification == Classification.UPDATE && related != null) put("superseded", related.path)
                        if (plan.classification == Classification.CONTRADICTION && related != null) {
                            put("contradicts", related.path)
                            put("message", "kept both memories; neither was overwritten")
                        }
                        if (plan.classification == Classification.UNCERTAIN) put("needsReview", true)
                    }
                }
                is GuardedWrite.Blocked -> {
                    audit.record(agent.agentId, "remember", "blocked", outcome.path, detail = outcome.findings.joinToString(", "))
                    return@guarded ToolResult.blocked(outcome.path, outcome.findings)
                }
                // Live state moved under the plan — loop and re-classify against what is there now.
                is GuardedWrite.Stale -> if (attempt == 1) {
                    audit.record(agent.agentId, "remember", "conflict", outcome.path, detail = "classification state moved twice")
                    return@guarded ToolResult.conflict {
                        put("path", outcome.path); put("expected", outcome.expected); put("current", outcome.current)
                        put("message", "memory changed while it was being classified; retry")
                    }
                }
            }
        }
        ToolResult.internalError("remember: classification did not converge")
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

    private fun buildMemoryNote(
        type: String,
        status: String,
        subject: String?,
        confidence: Double?,
        source: String?,
        content: String,
        /** Path of a memory this one contradicts — both are kept; neither wins silently. */
        contradicts: String? = null,
        /** Path of the memory this one replaces (the predecessor is revoked, not deleted). */
        supersedes: String? = null,
        /** Classification could not be settled; the note is persisted flagged for a human. */
        needsReview: Boolean = false,
    ): String {
        val fm = LinkedHashMap<String, Any?>()
        fm["type"] = type
        fm["status"] = status
        subject?.takeIf { it.isNotBlank() }?.let { fm["subject"] = it }
        confidence?.let { fm["confidence"] = it }
        source?.takeIf { it.isNotBlank() }?.let { fm["source"] = it }
        contradicts?.let { fm["contradicts"] = it }
        supersedes?.let { fm["supersedes"] = it }
        if (needsReview) fm["needs-review"] = true
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
                    put("agentId", agent.agentId); put("tool", tool); put("path", outcome.path); put("commit", outcome.commit); putVault()
                }
                eventBus.publish(EventTypes.COMMIT_CREATED) {
                    put("commit", outcome.commit); put("path", outcome.path); put("author", agent.author.name); put("agentId", agent.agentId); putVault()
                }
                ToolResult.ok { put("path", outcome.path); put("revision", outcome.revision); put("commit", outcome.commit) }
            }
            is WriteOutcome.Conflict -> {
                audit.record(agent.agentId, tool, "conflict", path, target, outcome.current)
                eventBus.publish(EventTypes.CONFLICT) { put("path", outcome.path); put("agentId", agent.agentId); put("tool", tool); putVault() }
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

        /**
         * How many of the top ranked blocks get their neighbourhood expanded (Ниво 1). Expanding
         * every hit would fill the budget with notes the query never matched.
         */
        const val EXPAND_SEEDS = 3

    }
}
