package dev.svod.engine.api

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.events.SvodEvent
import dev.svod.engine.graph.LinkGraph
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.MarkdownChunker
import dev.svod.engine.index.SearchFilters
import dev.svod.engine.index.SearchMode
import dev.svod.engine.index.SearchQuery
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The UI-facing App API: REST + WebSocket over Ktor, bound to 127.0.0.1 ONLY (invariant 7).
 * It is the contract surface defined by `contract/openapi.yaml`. Unlike the MCP endpoint it
 * has no per-agent auth — it is loopback-trusted and acts as a single UI identity.
 */
class AppApiServer(
    private val svod: SvodEngine,
    private val index: IndexService,
    private val eventBus: EventBus,
    private val config: Config = Config(),
    private val readiness: () -> Boolean = { true },
    private val conflicts: dev.svod.engine.sync.ConflictStore? = null,
) {
    data class Config(
        val host: String = "127.0.0.1",
        val apiVersion: String = "0.1.0",
        val embedderProvider: String = "onnx-local",
        val uiAuthor: Author = Author("svod-ui", "ui@svod.local"),
        /** When set to a directory, the reference web viewer is served same-origin at `/`. */
        val webViewerPath: String? = null,
    )

    class Running(val embedded: EmbeddedServer<*, *>, val port: Int) {
        fun stop() = embedded.stop(500, 1000)
    }

    // explicitNulls=false so optional fields (e.g. a file node's `children`) are omitted, not
    // emitted as null — which the contract's non-nullable arrays would reject.
    private val jsonFormat = Json { encodeDefaults = true; explicitNulls = false }

    fun start(requestedPort: Int = 0): Running {
        // index.updated events
        index.onSynced = { head -> eventBus.publish(EventTypes.INDEX_UPDATED) { put("head", head) } }
        eventBus.publish(EventTypes.ENGINE_STATUS) { put("status", "ready") }

        val embedded = embeddedServer(CIO, host = config.host, port = requestedPort) { module() }
        embedded.start(wait = false)
        val port = runBlocking { embedded.engine.resolvedConnectors().first().port }
        return Running(embedded, port)
    }

    private fun Application.module() {
        install(ContentNegotiation) { json(jsonFormat) }
        install(WebSockets)

        routing {
            get("/health") { call.respond(HealthDto()) }
            get("/ready") {
                val r = readiness()
                val ready = ReadyDto(ready = r, engine = true, index = index.docCount() >= 0)
                call.respond(if (r) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, ready)
            }

            get("/api/v1/tree") { call.respond(buildTree(svod.list())) }

            get("/api/v1/file") {
                val path = call.path() ?: return@get call.badRequest("missing path")
                val f = svod.read(path) ?: return@get call.notFound(path)
                call.respond(FileContentDto(f.path, f.revision, f.text))
            }
            put("/api/v1/file") {
                val path = call.path() ?: return@put call.badRequest("missing path")
                val body = call.receive<WriteRequestDto>()
                respondOutcome(svod.write(path, body.content, body.expectedRevision, config.uiAuthor), "write")
            }
            delete("/api/v1/file") {
                val path = call.path() ?: return@delete call.badRequest("missing path")
                val rev = call.request.queryParameters["expectedRevision"]
                respondOutcome(svod.delete(path, rev, config.uiAuthor), "delete")
            }

            post("/api/v1/file/move") {
                val req = call.receive<MoveRequestDto>()
                val moved = svod.moveWithLinks(req.from, req.to, req.expectedRevision, config.uiAuthor)
                when (val o = moved.outcome) {
                    is WriteOutcome.Success -> {
                        publishCommit(o, "move")
                        call.respond(MoveResultDto(o.path, o.revision, o.commit, moved.rewrittenBacklinks))
                    }
                    is WriteOutcome.Conflict -> { publishConflict(o.path); call.respond(HttpStatusCode.Conflict, o.toConflictDto()) }
                    is WriteOutcome.NotFound -> call.notFound(o.path)
                }
            }

            post("/api/v1/file/restore") {
                val req = call.receive<RestoreRequestDto>()
                respondOutcome(svod.restore(req.trashPath, req.to, config.uiAuthor), "restore")
            }

            get("/api/v1/file/history") {
                val path = call.path() ?: return@get call.badRequest("missing path")
                val max = call.request.queryParameters["max"]?.toIntOrNull() ?: 50
                call.respond(HistoryDto(svod.history(path, max).map { CommitInfoDto(it.commit, it.authorName, it.authorEmail, it.epochSeconds, it.message) }))
            }
            get("/api/v1/file/diff") {
                val path = call.path() ?: return@get call.badRequest("missing path")
                val from = call.request.queryParameters["from"] ?: return@get call.badRequest("missing from")
                val to = call.request.queryParameters["to"] ?: return@get call.badRequest("missing to")
                try {
                    call.respond(DiffResultDto(path, from, to, svod.diff(path, from, to)))
                } catch (e: IllegalArgumentException) {
                    call.badRequest(e.message ?: "invalid revision")
                }
            }
            get("/api/v1/file/revision") {
                val path = call.path() ?: return@get call.badRequest("missing path")
                val rev = call.request.queryParameters["revision"] ?: return@get call.badRequest("missing revision")
                val f = svod.getRevision(path, rev) ?: return@get call.notFound("$path@$rev")
                call.respond(FileContentDto(f.path, f.revision, f.text))
            }
            get("/api/v1/file/links") {
                val path = call.path() ?: return@get call.badRequest("missing path")
                val g = graph()
                call.respond(FileLinksDto(
                    path = path,
                    outlinks = g.outlinks(path).map { OutlinkDto(it.target, it.resolvedPath) },
                    backlinks = g.backlinks(path),
                    unresolved = g.unresolved(path),
                ))
            }

            get("/api/v1/search") {
                val q = call.request.queryParameters["q"] ?: return@get call.badRequest("missing q")
                val mode = when (call.request.queryParameters["mode"]?.lowercase()) {
                    "keyword" -> SearchMode.KEYWORD; "semantic" -> SearchMode.SEMANTIC; else -> SearchMode.HYBRID
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                val filters = SearchFilters(
                    tags = call.request.queryParameters.getAll("tags") ?: emptyList(),
                    pathPrefix = call.request.queryParameters["pathPrefix"],
                )
                val result = index.search(SearchQuery(q, filters, mode, limit))
                call.respond(SearchResultDto(result.mode.name, result.hits.map {
                    SearchHitDto(it.path, it.heading, it.snippet, it.score, it.matchedKeyword, it.matchedSemantic, it.tags)
                }))
            }

            get("/api/v1/graph") {
                val g = graph()
                call.respond(GraphDto(
                    nodes = g.nodePaths().map { GraphNodeDto(it, it) },
                    edges = g.edges().map { GraphEdgeDto(it.first, it.second) },
                    unresolved = g.unresolvedEdges().map { GraphEdgeDto(it.first, it.second) },
                ))
            }
            get("/api/v1/tags") { call.respond(TagsDto(tags())) }

            get("/api/v1/settings") {
                call.respond(SettingsDto(
                    vaultPath = svod.root.toString(),
                    apiVersion = config.apiVersion,
                    embedderProvider = config.embedderProvider,
                    embedderModel = index.indexedModel() ?: "none",
                    embedderDim = index.indexedDim() ?: 0,
                    host = config.host,
                ))
            }
            get("/api/v1/index/status") {
                call.respond(IndexStatusDto(index.docCount(), index.headCommitIndexed(), index.indexedModel() ?: "none", index.indexedDim() ?: 0))
            }
            get("/api/v1/conflicts") {
                val entries = conflicts?.all()?.map { ConflictEntryDto(it.path, it.reasons) } ?: emptyList()
                call.respond(ConflictsDto(entries))
            }

            webSocket("/api/v1/events") {
                eventBus.events.collect { e -> send(Frame.Text(encodeEvent(e))) }
            }

            // Opt-in: serve the reference web viewer same-origin (so its WS + fetch need no CORS).
            // Explicit API routes above take precedence; this catches everything else.
            config.webViewerPath?.let { viewerPath ->
                val dir = java.io.File(viewerPath)
                if (dir.isDirectory) staticFiles("/", dir) { default("index.html") }
            }
        }
    }

    // ---- helpers ----

    private fun encodeEvent(e: SvodEvent): String =
        jsonFormat.encodeToString(buildJsonObject { put("type", e.type); put("ts", e.ts); put("data", e.data) })

    private suspend fun io.ktor.server.routing.RoutingContext.respondOutcome(outcome: WriteOutcome, tool: String) {
        when (outcome) {
            is WriteOutcome.Success -> { publishCommit(outcome, tool); call.respond(WriteResultDto(outcome.path, outcome.revision, outcome.commit)) }
            is WriteOutcome.Conflict -> { publishConflict(outcome.path); call.respond(HttpStatusCode.Conflict, outcome.toConflictDto()) }
            is WriteOutcome.NotFound -> call.notFound(outcome.path)
        }
    }

    private fun publishCommit(s: WriteOutcome.Success, tool: String) {
        eventBus.publish(EventTypes.COMMIT_CREATED) { put("commit", s.commit); put("path", s.path); put("author", config.uiAuthor.name); put("tool", tool) }
        eventBus.publish(EventTypes.FILE_CHANGED) { put("path", s.path); put("source", "api") }
    }

    private fun publishConflict(path: String) = eventBus.publish(EventTypes.CONFLICT) { put("path", path); put("source", "api") }

    private fun WriteOutcome.Conflict.toConflictDto() = ConflictBodyDto(path, expected, current, currentContent)

    // Graph + tags cached by HEAD.
    private val graphLock = Any()
    private var cachedHead: String? = null
    private var cachedGraph: LinkGraph? = null
    private var cachedTags: List<TagCountDto>? = null

    private suspend fun snapshot(): Pair<LinkGraph, List<TagCountDto>> {
        val head = svod.head()
        synchronized(graphLock) {
            val g = cachedGraph; val t = cachedTags
            if (g != null && t != null && cachedHead == head) return g to t
        }
        val notes = svod.list().associateWith { svod.read(it)?.text ?: "" }
        val graph = LinkGraph.build(notes)
        val tagCounts = HashMap<String, Int>()
        for ((_, content) in notes) MarkdownChunker.parse(content).tags.forEach { tagCounts.merge(it, 1, Int::plus) }
        val tags = tagCounts.entries.sortedByDescending { it.value }.map { TagCountDto(it.key, it.value) }
        synchronized(graphLock) { cachedHead = head; cachedGraph = graph; cachedTags = tags }
        return graph to tags
    }

    private suspend fun graph(): LinkGraph = snapshot().first
    private suspend fun tags(): List<TagCountDto> = snapshot().second

    private fun buildTree(paths: List<String>): TreeNodeDto {
        class M(val name: String, val path: String) {
            val children = LinkedHashMap<String, M>()
            var isFile = false
        }
        val root = M("", "")
        for (p in paths) {
            val parts = p.split('/')
            var cur = root
            val sb = StringBuilder()
            for ((i, part) in parts.withIndex()) {
                if (sb.isNotEmpty()) sb.append('/'); sb.append(part)
                cur = cur.children.getOrPut(part) { M(part, sb.toString()) }
                if (i == parts.lastIndex) cur.isFile = true
            }
        }
        fun toDto(m: M): TreeNodeDto =
            if (m.isFile && m.children.isEmpty()) TreeNodeDto(m.name, m.path, "file")
            else TreeNodeDto(m.name, m.path, "dir", m.children.values.map { toDto(it) })
        return toDto(root)
    }
}

private fun io.ktor.server.application.ApplicationCall.path(): String? =
    request.queryParameters["path"]?.takeIf { it.isNotEmpty() }

private suspend fun io.ktor.server.application.ApplicationCall.notFound(what: String) =
    respond(HttpStatusCode.NotFound, ErrorDto("not_found", what))

private suspend fun io.ktor.server.application.ApplicationCall.badRequest(message: String) =
    respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", message))
