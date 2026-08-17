package dev.svod.engine.api

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.events.EventBus
import dev.svod.engine.events.EventTypes
import dev.svod.engine.events.SvodEvent
import dev.svod.engine.graph.LinkGraph
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.SearchFilters
import dev.svod.engine.index.SearchMode
import dev.svod.engine.index.SearchQuery
import dev.svod.engine.memory.MemoryStore
import dev.svod.engine.memory.Proposal
import dev.svod.engine.memory.SESSIONS_PREFIX
import dev.svod.engine.memory.SessionMeta
import dev.svod.engine.memory.SessionNotes
import dev.svod.engine.sync.ConflictStore
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * The UI-facing App API: REST + WebSocket over Ktor, bound to 127.0.0.1 ONLY (invariant 7).
 * It is the contract surface defined by `contract/openapi.yaml`. Unlike the MCP endpoint it
 * has no per-agent auth — it is loopback-trusted and acts as a single UI identity.
 *
 * Multi-vault: every per-vault route resolves an optional `?vault=` (default vault when omitted)
 * via the [VaultRouter]. An unknown vault id is a 404. The single-vault convenience constructor
 * preserves the original behavior for tests and simple embeds.
 */
class AppApiServer(
    private val vaults: VaultRouter,
    private val eventBus: EventBus,
    private val config: Config = Config(),
    private val readiness: () -> Boolean = { true },
    /** Per-vault off-site backup; null ⇒ backup unconfigured (POST /backup/now is a graceful no-op). */
    private val backup: dev.svod.engine.sync.BackupService? = null,
    /** Per-vault sync + backup configuration for GET /sync/config; default ⇒ solo, no peers/backup. */
    private val syncConfig: (VaultView) -> SyncConfigDto = { SyncConfigDto(role = "solo") },
    /** Per-vault sync status for the GET /vaults `sync` field; null ⇒ no sync/backup dot. */
    private val vaultStatus: (VaultView) -> SyncStatusDto? = { null },
    /** Run one real reconcile cycle for a synced vault; null result ⇒ not a synced vault (no peers). */
    private val syncNow: suspend (VaultView) -> dev.svod.engine.sync.SyncEngine.Result? = { null },
    /** Live: is a filesystem watcher currently running for this (vault, source)? Default false. */
    private val sourceWatching: (VaultView, String) -> Boolean = { _, _ -> false },
    /** Called after a source register/PATCH/remove so the watcher set is reconciled (no restart). */
    private val reconcileSourceWatchers: (VaultView) -> Unit = { },
    /** Runtime embedder control; null ⇒ PUT /embedder & POST /embedder/test return 501. */
    private val embedderControl: EmbedderControl? = null,
    /** Runtime vault creation; null ⇒ POST /vaults returns 501. */
    private val vaultCreator: VaultCreator? = null,
    /** Runtime vault removal; null ⇒ DELETE /vaults/{id} returns 501. */
    private val vaultRemover: VaultRemover? = null,
    /** Runtime agent management; null ⇒ /agents endpoints return 501. */
    private val agentAdmin: AgentAdmin? = null,
    /** Runtime self-update; null ⇒ /update endpoints return 501. */
    private val updateAdmin: UpdateAdmin? = null,
) {
    /** Back-compat single-vault constructor (one engine/index, optional conflicts + sync status). */
    constructor(
        svod: SvodEngine,
        index: IndexService,
        eventBus: EventBus,
        config: Config = Config(),
        readiness: () -> Boolean = { true },
        conflicts: ConflictStore? = null,
        syncStatus: () -> SyncStatusDto? = { null },
        vaultCreator: VaultCreator? = null,
        vaultRemover: VaultRemover? = null,
    ) : this(SingleVaultRouter(svod, index, conflicts, syncStatus), eventBus, config, readiness, vaultCreator = vaultCreator, vaultRemover = vaultRemover)

    data class Config(
        val host: String = "127.0.0.1",
        /**
         * The App API contract version advertised to clients (the macOS app feature-detects on it).
         * Derived from [ApiCompatibility.CURRENT_CONTRACT_VERSION] rather than repeated as a literal:
         * when this was its own hardcoded string, bumping the contract for 1.12.0 moved the
         * self-update gate to 0.23.0 while `/settings` kept advertising 0.22.0.
         */
        val apiVersion: String = dev.svod.engine.lifecycle.ApiCompatibility.CURRENT_CONTRACT_VERSION,
        val embedderProvider: String = "onnx-local",
        /** Effective embedder model/endpoint for the read-only settings view (null endpoint = in-process). */
        val embedderModel: String = "none",
        val embedderEndpoint: String? = null,
        val uiAuthor: Author = Author("svod-ui", "ui@svod.local"),
        /** When set to a directory, the reference web viewer is served same-origin at `/`. */
        val webViewerPath: String? = null,
    )

    class Running(val embedded: EmbeddedServer<*, *>, val port: Int, private val onStop: () -> Unit = {}) {
        fun stop() { try { embedded.stop(500, 1000) } finally { onStop() } }
    }

    // explicitNulls=false so optional fields (e.g. a file node's `children`) are omitted, not
    // emitted as null — which the contract's non-nullable arrays would reject.
    private val jsonFormat = Json { encodeDefaults = true; explicitNulls = false }

    fun start(requestedPort: Int = 0): Running {
        eventBus.publish(EventTypes.ENGINE_STATUS) { put("status", "ready") }

        val embedded = embeddedServer(CIO, host = config.host, port = requestedPort) { module() }
        embedded.start(wait = false)
        val port = runBlocking { embedded.engine.resolvedConnectors().first().port }
        return Running(embedded, port) { linkIndexes.values.forEach { runCatching { it.close() } } }
    }

    /** Resolve the request's target vault (`?vault=`, default when omitted); null ⇒ unknown id. */
    private fun RoutingContext.vault(): VaultView? = vaults.resolve(call.request.queryParameters["vault"])

    private fun Application.module() {
        install(ContentNegotiation) { json(jsonFormat) }
        install(WebSockets)

        routing {
            get("/health") { call.respond(HealthDto()) }
            get("/ready") {
                val r = readiness()
                val ready = ReadyDto(ready = r, engine = true, index = vaults.resolve(null)!!.index.docCount() >= 0)
                call.respond(if (r) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, ready)
            }

            // Prometheus scrape endpoint (loopback ops surface). Deliberately OUTSIDE the versioned
            // /api/v1 contract — it is for monitoring, not the UI, so it carries no apiVersion and is
            // not part of contract/openapi.yaml. The JSON /api/v1/metrics remains the UI-facing view.
            get("/metrics") { call.respondText(prometheusExposition(), ContentType.Text.Plain) }

            get("/api/v1/vaults") {
                call.respond(VaultsDto(vaults.all().map { VaultInfoDto(it.id, it.name, it.id == vaults.defaultId(), vaultStatus(it)) }))
            }
            post("/api/v1/vaults") {
                val creator = vaultCreator ?: return@post call.notImplemented("vault creation")
                val req = call.receive<CreateVaultRequest>()
                try {
                    val v = creator.create(req)
                    // Same row shape as a GET /vaults item (default flag + sync status computed identically).
                    call.respond(HttpStatusCode.Created, VaultInfoDto(v.id, v.name, v.id == vaults.defaultId(), vaultStatus(v)))
                } catch (e: VaultCreator.InvalidRequest) {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", e.message ?: "invalid vault request"))
                } catch (e: VaultCreator.Conflict) {
                    call.respond(HttpStatusCode.Conflict, ErrorDto("conflict", e.message ?: "vault already exists"))
                } catch (e: VaultCreator.NotWritable) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("not_writable", e.message ?: "vault path not writable"))
                }
            }
            delete("/api/v1/vaults/{id}") {
                val remover = vaultRemover ?: return@delete call.notImplemented("vault deletion")
                val id = call.parameters["id"] ?: return@delete call.badRequest("missing vault id")
                val deleteFiles = call.request.queryParameters["deleteFiles"]?.toBooleanStrictOrNull() ?: false
                try {
                    // The engine does the logical removal (lock/handles released) before responding; the
                    // caller disposes of `path` (e.g. moves it to the OS Trash) unless deleteFiles=true.
                    call.respond(remover.delete(id, deleteFiles))
                } catch (e: VaultRemover.UnknownVault) {
                    call.notFound(e.message ?: "unknown vault")
                } catch (e: VaultRemover.Conflict) {
                    call.respond(HttpStatusCode.Conflict, ErrorDto("conflict", e.message ?: "vault cannot be deleted"))
                }
            }

            get("/api/v1/agents") {
                val admin = agentAdmin ?: return@get call.notImplemented("agent management")
                val view = admin.list()
                val effectiveHost = if (config.host == "0.0.0.0") "127.0.0.1" else config.host
                val mcpUrl = "http://$effectiveHost:${view.mcpPort}"
                call.respond(AgentsDto(view.agents.map { it.toDto() }, view.mcpPort, mcpUrl))
            }
            post("/api/v1/agents") {
                val admin = agentAdmin ?: return@post call.notImplemented("agent management")
                val req = call.receive<CreateAgentRequest>()
                try {
                    call.respond(HttpStatusCode.Created, admin.create(req).toDto())
                } catch (e: AgentAdmin.InvalidRequest) {
                    call.badRequest(e.message ?: "invalid agent request")
                } catch (e: AgentAdmin.Conflict) {
                    call.respond(HttpStatusCode.Conflict, ErrorDto("conflict", e.message ?: "agent already exists"))
                } catch (e: AgentAdmin.NotARef) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("not_a_ref", e.message ?: "tokenRef must be a Secrets ref"))
                }
            }
            put("/api/v1/agents/{id}") {
                val admin = agentAdmin ?: return@put call.notImplemented("agent management")
                val id = call.parameters["id"] ?: return@put call.badRequest("missing agent id")
                val req = call.receive<UpdateAgentRequest>()
                try {
                    call.respond(admin.update(id, req).toDto())
                } catch (e: AgentAdmin.UnknownAgent) {
                    call.notFound(e.message ?: "unknown agent")
                } catch (e: AgentAdmin.InvalidRequest) {
                    call.badRequest(e.message ?: "invalid agent request")
                } catch (e: AgentAdmin.NotARef) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("not_a_ref", e.message ?: "tokenRef must be a Secrets ref"))
                }
            }
            delete("/api/v1/agents/{id}") {
                val admin = agentAdmin ?: return@delete call.notImplemented("agent management")
                val id = call.parameters["id"] ?: return@delete call.badRequest("missing agent id")
                try {
                    admin.delete(id)
                    call.respond(kotlinx.serialization.json.buildJsonObject { put("agentId", id) })
                } catch (e: AgentAdmin.UnknownAgent) {
                    call.notFound(e.message ?: "unknown agent")
                }
            }

            get("/api/v1/update/check") {
                val admin = updateAdmin ?: return@get call.notImplemented("update")
                call.respond(admin.check())
            }
            post("/api/v1/update/apply") {
                val admin = updateAdmin ?: return@post call.notImplemented("update")
                try {
                    call.respond(HttpStatusCode.Accepted, admin.apply())
                } catch (e: UpdateAdmin.NotApplicable) {
                    call.respond(HttpStatusCode.Conflict, ErrorDto("conflict", e.message ?: ""))
                } catch (e: UpdateAdmin.NotSupported) {
                    call.notImplemented("update")
                }
            }

            get("/api/v1/tree") {
                val vc = vault() ?: return@get call.notFound("vault")
                call.respond(buildTree(vc.engine.list()))
            }

            get("/api/v1/file") {
                val vc = vault() ?: return@get call.notFound("vault")
                val path = call.path() ?: return@get call.badRequest("missing path")
                val f = vc.engine.read(path) ?: return@get call.notFound(path)
                call.respond(FileContentDto(f.path, f.revision, f.text))
            }
            put("/api/v1/file") {
                val vc = vault() ?: return@put call.notFound("vault")
                val path = call.path() ?: return@put call.badRequest("missing path")
                val body = call.receive<WriteRequestDto>()
                respondOutcome(vc, vc.engine.write(path, body.content, body.expectedRevision, config.uiAuthor), "write")
            }
            delete("/api/v1/file") {
                val vc = vault() ?: return@delete call.notFound("vault")
                val path = call.path() ?: return@delete call.badRequest("missing path")
                val rev = call.request.queryParameters["expectedRevision"]
                respondOutcome(vc, vc.engine.delete(path, rev, config.uiAuthor), "delete")
            }

            post("/api/v1/file/move") {
                val vc = vault() ?: return@post call.notFound("vault")
                val req = call.receive<MoveRequestDto>()
                val moved = vc.engine.moveWithLinks(req.from, req.to, req.expectedRevision, config.uiAuthor)
                when (val o = moved.outcome) {
                    is WriteOutcome.Success -> {
                        publishCommit(vc, o, "move")
                        // Cross-vault link integrity (D7): rewrite qualified [[vault:note]] backlinks
                        // living in OTHER vaults. Best-effort, per-repo commits — never atomic across
                        // repos, never loses data (the move already stands; a failed rewrite just
                        // leaves a stale link that surfaces as a cross-vault backlink to fix).
                        if (vaults.ids().size > 1) runCatching { crossVaultRelink(vc.id, req.from, req.to) }
                        call.respond(MoveResultDto(o.path, o.revision, o.commit, moved.rewrittenBacklinks))
                    }
                    is WriteOutcome.Conflict -> { publishConflict(vc, o.path); call.respond(HttpStatusCode.Conflict, o.toConflictDto()) }
                    is WriteOutcome.NotFound -> call.notFound(o.path)
                    is WriteOutcome.Blocked -> call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("blocked", o.findings.joinToString(", ")))
                }
            }

            post("/api/v1/file/restore") {
                val vc = vault() ?: return@post call.notFound("vault")
                val req = call.receive<RestoreRequestDto>()
                respondOutcome(vc, vc.engine.restore(req.trashPath, req.to, config.uiAuthor), "restore")
            }

            get("/api/v1/file/history") {
                val vc = vault() ?: return@get call.notFound("vault")
                val path = call.path() ?: return@get call.badRequest("missing path")
                val max = call.request.queryParameters["max"]?.toIntOrNull() ?: 100
                call.respond(HistoryDto(vc.engine.history(path, max).map { CommitInfoDto(it.commit, it.authorName, it.authorEmail, it.epochSeconds, it.message) }))
            }
            get("/api/v1/file/diff") {
                val vc = vault() ?: return@get call.notFound("vault")
                val path = call.path() ?: return@get call.badRequest("missing path")
                val from = call.request.queryParameters["from"] ?: return@get call.badRequest("missing from")
                val to = call.request.queryParameters["to"] ?: return@get call.badRequest("missing to")
                try {
                    call.respond(DiffResultDto(path, from, to, vc.engine.diff(path, from, to)))
                } catch (e: IllegalArgumentException) {
                    call.badRequest(e.message ?: "invalid revision")
                }
            }
            get("/api/v1/file/revision") {
                val vc = vault() ?: return@get call.notFound("vault")
                val path = call.path() ?: return@get call.badRequest("missing path")
                val rev = call.request.queryParameters["revision"] ?: return@get call.badRequest("missing revision")
                val f = vc.engine.getRevision(path, rev) ?: return@get call.notFound("$path@$rev")
                call.respond(FileContentDto(f.path, f.revision, f.text))
            }
            get("/api/v1/file/links") {
                val vc = vault() ?: return@get call.notFound("vault")
                val path = call.path() ?: return@get call.badRequest("missing path")
                val g = graph(vc)
                // Cross-vault backlinks (qualified [[vault:note]] from other vaults) come from the
                // federated graph; local backlinks stay as bare paths for back-compat.
                val cross = if (vaults.ids().size > 1)
                    federated().backlinks(vc.id, path).filter { !it.startsWith("${vc.id}:") }
                else emptyList()
                call.respond(FileLinksDto(
                    path = path,
                    outlinks = g.outlinks(path).map { OutlinkDto(it.target, it.resolvedPath) },
                    backlinks = g.backlinks(path),
                    unresolved = g.unresolved(path),
                    crossVaultBacklinks = cross,
                ))
            }

            get("/api/v1/search") {
                val vc = vault() ?: return@get call.notFound("vault")
                val mode = when (call.request.queryParameters["mode"]?.lowercase()) {
                    "keyword" -> SearchMode.KEYWORD; "semantic" -> SearchMode.SEMANTIC; else -> SearchMode.HYBRID
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                val filters = SearchFilters(
                    tags = call.request.queryParameters.getAll("tags") ?: emptyList(),
                    pathPrefix = call.request.queryParameters["pathPrefix"],
                    type = call.request.queryParameters["type"]?.takeIf { it.isNotBlank() },
                    status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() },
                    includeAll = call.request.queryParameters["includeAll"]?.equals("true", ignoreCase = true) == true,
                )
                // `q` is optional when a filter is present: a filter-only query browses by tag/prefix
                // (every note carrying the tag). With neither a query nor a filter there's nothing to do.
                val q = call.request.queryParameters["q"] ?: ""
                if (q.isBlank() && filters.isEmpty) return@get call.badRequest("provide q or a filter (e.g. tags)")
                val across = call.request.queryParameters["across"]?.equals("true", ignoreCase = true) == true
                if (across) {
                    // Federated: query every vault, tag each hit with its vault, merge by score.
                    val hits = ArrayList<SearchHitDto>()
                    for (v in vaults.all()) {
                        val r = v.index.search(SearchQuery(q, filters, mode, limit))
                        r.hits.forEach { hits.add(SearchHitDto(it.path, it.heading, it.snippet, it.score, it.matchedKeyword, it.matchedSemantic, it.tags, v.id, dev.svod.engine.index.estimateTokens(it.snippet))) }
                    }
                    hits.sortByDescending { it.score }
                    call.respond(SearchResultDto(mode.name, hits.take(limit)))
                } else {
                    val result = vc.index.search(SearchQuery(q, filters, mode, limit))
                    call.respond(SearchResultDto(result.mode.name, result.hits.map {
                        SearchHitDto(it.path, it.heading, it.snippet, it.score, it.matchedKeyword, it.matchedSemantic, it.tags, vc.id, dev.svod.engine.index.estimateTokens(it.snippet))
                    }))
                }
            }

            get("/api/v1/graph") {
                val vc = vault() ?: return@get call.notFound("vault")
                val g = graph(vc)
                call.respond(GraphDto(
                    nodes = g.nodePaths().map { GraphNodeDto(it, it) },
                    edges = g.edges().map { GraphEdgeDto(it.first, it.second) },
                    unresolved = g.unresolvedEdges().map { GraphEdgeDto(it.first, it.second) },
                ))
            }
            // Derived thematic graph (Ниво 2). Separate from /api/v1/graph, which stays the raw
            // wikilink graph the UI already renders — these are additive routes, not a replacement.
            get("/api/v1/graph/communities") {
                val vc = vault() ?: return@get call.notFound("vault")
                val g = vc.graph
                    ?: return@get call.respond(GraphCommunitiesDto(state = "NOT_BUILT", stale = false, communities = emptyList()))
                val query = call.request.queryParameters["query"]?.takeIf { it.isNotBlank() }
                val level = call.request.queryParameters["level"]?.toIntOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                // Default `full` for back-compat: app v0.2.16 shipped against 0.24.0 and reads
                // `members`. New callers should ask for `sample` — the full lists dominate the
                // payload (~97% of it on a 3k-note vault) and are rarely all needed at once.
                val members = call.request.queryParameters["members"] ?: "full"
                val status = g.status()
                call.respond(GraphCommunitiesDto(
                    state = status.state,
                    stale = status.stale,
                    communities = g.communities(query, level, limit.coerceIn(1, 200)).map { c ->
                        val shown = when (members.lowercase()) {
                            "none" -> emptyList()
                            "sample" -> c.members.take(dev.svod.engine.graphrag.MEMBER_SAMPLE)
                            else -> c.members
                        }
                        GraphCommunityDto(c.id, c.level, c.title, c.summary, c.size, shown)
                    },
                ))
            }
            get("/api/v1/graph/community") {
                val vc = vault() ?: return@get call.notFound("vault")
                val id = call.request.queryParameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "id is required"))
                val c = vc.graph?.community(id) ?: return@get call.notFound("community $id")
                call.respond(GraphCommunityDto(c.id, c.level, c.title, c.summary, c.size, c.members))
            }
            get("/api/v1/graph/status") {
                val vc = vault() ?: return@get call.notFound("vault")
                call.respond(vc.graph?.status() ?: dev.svod.engine.graphrag.GraphStatus(state = "NOT_BUILT", enabled = false))
            }
            post("/api/v1/graph/rebuild") {
                val vc = vault() ?: return@post call.notFound("vault")
                val g = vc.graph
                    ?: return@post call.respond(HttpStatusCode.Conflict, ErrorDto("graph_disabled", "the graph feature is not enabled for this vault"))
                if (!g.rebuild()) {
                    return@post call.respond(HttpStatusCode.Conflict, ErrorDto("graph_busy", "a build is already running, or the feature is disabled"))
                }
                call.respond(HttpStatusCode.Accepted, g.status())
            }

            get("/api/v1/tags") {
                val vc = vault() ?: return@get call.notFound("vault")
                call.respond(TagsDto(tags(vc)))
            }

            get("/api/v1/settings") {
                val vc = vault() ?: return@get call.notFound("vault")
                val emb = embedderInfo(vc)
                call.respond(SettingsDto(
                    vaultPath = vc.engine.root.toString(),
                    apiVersion = config.apiVersion,
                    embedderProvider = emb.provider,
                    embedderModel = vc.index.indexedModel() ?: emb.model,
                    embedderDim = emb.dimension,
                    host = config.host,
                    embedder = emb,
                    reranker = vc.index.rerankerInfo().let { RerankerInfoDto(it.provider, it.model, it.active) },
                ))
            }
            get("/api/v1/index/status") {
                val vc = vault() ?: return@get call.notFound("vault")
                call.respond(indexStatus(vc))
            }
            put("/api/v1/embedder") {
                val vc = vault() ?: return@put call.notFound("vault")
                val control = embedderControl ?: return@put call.notImplemented("embedder control")
                val req = call.receive<EmbedderRequestDto>()
                try {
                    val d = control.apply(vc.id, req.toSpec())
                    call.respond(EmbedderInfoDto(d.provider, d.model, d.endpoint, d.dimension))
                } catch (e: EmbedderControl.InvalidSpec) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("invalid_embedder", e.message ?: "invalid embedder spec"))
                } catch (e: Exception) {
                    // building a remote embedder probes the endpoint; an unreachable endpoint is a 409.
                    call.respond(HttpStatusCode.Conflict, ErrorDto("embedder_unavailable", (e.cause ?: e).message ?: "could not initialize embedder"))
                }
            }
            post("/api/v1/embedder/test") {
                val vc = vault() ?: return@post call.notFound("vault")
                val control = embedderControl ?: return@post call.notImplemented("embedder control")
                val req = call.receive<EmbedderRequestDto>()
                val r = control.test(vc.id, req.toSpec())
                call.respond(EmbedderTestResultDto(r.ok, r.dimension, r.latencyMs, r.error))
            }
            post("/api/v1/embedder/models") {
                val vc = vault() ?: return@post call.notFound("vault")
                val control = embedderControl ?: return@post call.notImplemented("embedder control")
                val req = call.receive<EmbedderRequestDto>()
                try {
                    val r = control.models(vc.id, req.toSpec())
                    call.respond(EmbedderModelsDto(r.provider, r.models.map { EmbedderModelOptionDto(it.id, it.dimension) }))
                } catch (e: EmbedderControl.InvalidSpec) {
                    // A raw API key (not a Secrets ref) or an unknown provider is the only 4xx here; an
                    // unreachable endpoint returns 200 with an empty list (the controller swallows it).
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("invalid_embedder", e.message ?: "invalid embedder spec"))
                }
            }
            post("/api/v1/index/reembed") {
                val vc = vault() ?: return@post call.notFound("vault")
                vc.index.reembed()
                call.respond(indexStatus(vc))
            }
            post("/api/v1/index/pause") {
                val vc = vault() ?: return@post call.notFound("vault")
                vc.index.pause()
                call.respond(indexStatus(vc))
            }
            post("/api/v1/index/resume") {
                val vc = vault() ?: return@post call.notFound("vault")
                vc.index.resume()
                call.respond(indexStatus(vc))
            }
            get("/api/v1/conflicts") {
                val vc = vault() ?: return@get call.notFound("vault")
                val entries = vc.conflicts?.all()?.map {
                    ConflictEntryDto(it.path, it.reasons, it.base, it.ours, it.theirs, it.ts)
                } ?: emptyList()
                call.respond(ConflictsDto(entries))
            }
            post("/api/v1/conflicts/resolve") {
                val vc = vault() ?: return@post call.notFound("vault")
                val req = call.receive<ResolveConflictRequestDto>()
                // The resolution is committed through the single writer like any other write; on
                // success we clear the surfaced conflict. Default guard is the current on-disk
                // revision, so a change landing after the client resolved still yields 409 rather
                // than a silent overwrite (invariant 4); a client may pin its own expectedRevision.
                val expected = req.expectedRevision ?: vc.engine.read(req.path)?.revision
                when (val o = vc.engine.write(req.path, req.content, expected, config.uiAuthor)) {
                    is WriteOutcome.Success -> {
                        vc.conflicts?.resolve(req.path)
                        publishCommit(vc, o, "resolve")
                        call.respond(WriteResultDto(o.path, o.revision, o.commit))
                    }
                    is WriteOutcome.Conflict -> { publishConflict(vc, o.path); call.respond(HttpStatusCode.Conflict, o.toConflictDto()) }
                    is WriteOutcome.NotFound -> call.notFound(o.path)
                    is WriteOutcome.Blocked -> call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("blocked", "secret(s) detected: ${o.findings.joinToString(", ")}"))
                }
            }
            post("/api/v1/import") {
                val vc = vault() ?: return@post call.notFound("vault")
                val req = call.receive<ImportRequestDto>()
                // `source` is an arbitrary local directory by design — importing an Obsidian vault
                // means pointing at a folder OUTSIDE the Svod vault (a "choose folder" action). This
                // is safe under invariant 7: the App API is loopback-only and acts as the trusted
                // local UI identity, and import is deliberately NOT an MCP tool, so a remote agent
                // cannot reach it. Restricting `source` to vault roots would break the feature.
                val src = java.nio.file.Paths.get(req.source)
                if (!java.nio.file.Files.isDirectory(src)) return@post call.badRequest("source is not a directory: ${req.source}")
                val r = dev.svod.engine.migrate.ObsidianImport.import(src, vc.engine, into = req.into ?: "", followSymlinks = req.followSymlinks)
                call.respond(ImportResultDto(r.imported, r.unchanged, r.skipped))
            }
            get("/api/v1/metrics") {
                val vc = vault() ?: return@get call.notFound("vault")
                val w = vc.engine.metrics.snapshot()
                val head = vc.engine.head()
                val indexedHead = vc.index.headCommitIndexed()
                call.respond(MetricsDto(
                    write = WriteStatsDto(w.count, w.avgMs, w.maxMs, w.lastMs),
                    queueDepth = vc.engine.queueDepth(),
                    peakQueueDepth = vc.engine.peakQueueDepth(),
                    index = IndexLagDto(vc.index.docCount(), head, indexedHead, head != indexedHead),
                    conflicts = vc.conflicts?.all()?.size ?: 0,
                    sync = vc.syncStatus(),
                ))
            }

            // ---- Ops surface: sync config, backup, maintenance (backup & disaster recovery) ----

            get("/api/v1/sync/config") {
                val vc = vault() ?: return@get call.notFound("vault")
                // syncConfig redacts any credentials embedded in peer/backup remote URLs.
                call.respond(syncConfig(vc))
            }

            post("/api/v1/sync/now") {
                val vc = vault() ?: return@post call.notFound("vault")
                // Not a synced vault ⇒ nothing to reconcile: a successful no-op (ok=false), not an error.
                val r = syncNow(vc)
                    ?: return@post call.respond(SyncAckDto(ok = false, head = vc.engine.head(), conflicts = 0))
                when (r.status) {
                    // Reconciled (incl. surfaced conflicts — a successful sync that found overlaps, the
                    // user resolves via /conflicts/resolve; conflicts is NOT a transport failure).
                    dev.svod.engine.sync.SyncEngine.Status.inSync,
                    dev.svod.engine.sync.SyncEngine.Status.syncing,
                    dev.svod.engine.sync.SyncEngine.Status.conflicts ->
                        call.respond(SyncAckDto(ok = true, head = r.head, conflicts = r.conflicts))
                    // A transport failure (remote unreachable / auth / push rejected past retry) → 409, never 500.
                    dev.svod.engine.sync.SyncEngine.Status.offline,
                    dev.svod.engine.sync.SyncEngine.Status.error ->
                        call.respond(HttpStatusCode.Conflict, ErrorDto(
                            "sync_failed",
                            "sync failed (${r.status}) — check the remote is reachable and its credentials resolve",
                        ))
                }
            }

            post("/api/v1/backup/now") {
                val vc = vault() ?: return@post call.notFound("vault")
                val cfg = backup?.configOf(vc.id)
                if (cfg == null || !cfg.enabled) {
                    return@post call.respond(HttpStatusCode.Conflict, ErrorDto(
                        "no_backup_remote",
                        "no backup remote is configured for vault '${vc.id}' — set one via PUT /api/v1/settings/backup",
                    ))
                }
                // Back up THIS vault to its own remote (each environment to its own server).
                val r = backup.backupNow(vc.id)
                when (r.status) {
                    "ok" -> call.respond(BackupAckDto(ok = true, head = r.head))
                    // Nothing new to push (already up to date / another backup in flight / empty repo)
                    // is a SUCCESS, not a failure — the off-site copy is current. ok=false is reserved
                    // for real push failures below.
                    "noop" -> call.respond(BackupAckDto(ok = true, head = r.head, noChange = true))
                    else -> call.respond(HttpStatusCode.Conflict, ErrorDto(
                        "backup_failed",
                        "backup push failed (${r.status}) — check the remote is reachable and its credentials resolve",
                    ))
                }
            }

            put("/api/v1/settings/backup") {
                val vc = vault() ?: return@put call.notFound("vault")
                val req = call.receive<BackupConfigRequestDto>()
                if (req.remote.isBlank()) {
                    return@put call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("invalid_remote", "backup remote must be non-blank"))
                }
                if (dev.svod.engine.lifecycle.SvodConfig.remoteHasInlineCredentials(req.remote)) {
                    return@put call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto(
                        "inline_credentials",
                        "backup remote must not embed credentials inline; reference them as a Secrets ref (keychain:/env:/file:)",
                    ))
                }
                // Set + persist THIS vault's backup remote + auto-backup schedule (survives restart via
                // its BackupConfigStore), then return the vault's updated, redacted sync+backup config.
                backup?.configure(vc.id, dev.svod.engine.lifecycle.SvodConfig.BackupSettings(
                    req.remote, req.enabled, req.backupOnStartup, req.backupIntervalMinutes, req.backupOnChange,
                    req.syncEnabled, req.syncIntervalMinutes,
                ))
                call.respond(syncConfig(vc))
            }

            post("/api/v1/maintenance/reindex") {
                val vc = vault() ?: return@post call.notFound("vault")
                // Full HEAD reconcile (self-heal): rebuilds the index from git HEAD.
                vc.index.reconcileNow()
                call.respond(MaintenanceAckDto(started = true, docCount = vc.index.docCount()))
            }

            // ---- External sources: register external paths and re-sync their content into the vault ----

            get("/api/v1/sources") {
                val vc = vault() ?: return@get call.notFound("vault")
                val store = dev.svod.engine.sources.ExternalSourceStore(vc.engine.root)
                call.respond(store.list().map { it.toDto(sourceWatching(vc, it.id)) })
            }

            post("/api/v1/sources") {
                val vc = vault() ?: return@post call.notFound("vault")
                val req = call.receive<RegisterSourceRequestDto>()
                val path = java.nio.file.Paths.get(req.path)
                if (!path.isAbsolute) {
                    return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("invalid_path", "source path must be absolute: ${req.path}"))
                }
                if (!java.nio.file.Files.exists(path)) {
                    return@post call.badRequest("source path does not exist: ${req.path}")
                }
                val abs = path.normalize().toString()
                val store = dev.svod.engine.sources.ExternalSourceStore(vc.engine.root)
                val source = dev.svod.engine.sources.ExternalSource(
                    id = dev.svod.engine.sources.ExternalSource.idFor(abs),
                    path = abs,
                    into = (req.into ?: "").trim('/'),
                    followSymlinks = req.followSymlinks,
                    prune = req.prune,
                    autoSync = req.autoSync,
                    writeBack = req.writeBack,
                )
                val stored = store.put(source)
                reconcileSourceWatchers(vc) // start/stop the watcher to match the new registration
                call.respond(stored.toDto(sourceWatching(vc, stored.id)))
            }

            patch("/api/v1/sources/{id}") {
                val vc = vault() ?: return@patch call.notFound("vault")
                val id = call.parameters["id"] ?: return@patch call.badRequest("missing source id")
                val req = call.receive<PatchSourceRequestDto>()
                val store = dev.svod.engine.sources.ExternalSourceStore(vc.engine.root)
                val current = store.get(id) ?: return@patch call.notFound("source '$id'")
                // null fields leave that setting unchanged; toggling autoSync (re)starts/stops the watcher.
                val updated = store.put(current.copy(
                    autoSync = req.autoSync ?: current.autoSync,
                    followSymlinks = req.followSymlinks ?: current.followSymlinks,
                    prune = req.prune ?: current.prune,
                    writeBack = req.writeBack ?: current.writeBack,
                ))
                reconcileSourceWatchers(vc)
                call.respond(updated.toDto(sourceWatching(vc, updated.id)))
            }

            delete("/api/v1/sources/{id}") {
                val vc = vault() ?: return@delete call.notFound("vault")
                val id = call.parameters["id"] ?: return@delete call.badRequest("missing source id")
                val store = dev.svod.engine.sources.ExternalSourceStore(vc.engine.root)
                val removed = store.remove(id)
                if (removed) reconcileSourceWatchers(vc) // stop the watcher for the removed source
                if (removed) call.respond(HttpStatusCode.NoContent) else call.notFound("source '$id'")
            }

            post("/api/v1/sources/sync") {
                val vc = vault() ?: return@post call.notFound("vault")
                val store = dev.svod.engine.sources.ExternalSourceStore(vc.engine.root)
                val sync = dev.svod.engine.sources.SourceSync(vc.engine, store)
                call.respond(store.list().map { sync.sync(it).toDto() })
            }

            post("/api/v1/sources/{id}/sync") {
                val vc = vault() ?: return@post call.notFound("vault")
                val id = call.parameters["id"] ?: return@post call.badRequest("missing source id")
                val store = dev.svod.engine.sources.ExternalSourceStore(vc.engine.root)
                val source = store.get(id) ?: return@post call.notFound("source '$id'")
                val result = dev.svod.engine.sources.SourceSync(vc.engine, store).sync(source)
                call.respond(result.toDto())
            }

            post("/api/v1/sources/{id}/resolve") {
                val vc = vault() ?: return@post call.notFound("vault")
                val id = call.parameters["id"] ?: return@post call.badRequest("missing source id")
                val store = dev.svod.engine.sources.ExternalSourceStore(vc.engine.root)
                val source = store.get(id) ?: return@post call.notFound("source '$id'")
                val req = call.receive<SourceResolveRequest>()
                val strategy = when (req.strategy) {
                    "takeExternal" -> dev.svod.engine.sources.ResolveStrategy.TAKE_EXTERNAL
                    "keepVault" -> dev.svod.engine.sources.ResolveStrategy.KEEP_VAULT
                    else -> return@post call.badRequest("strategy must be takeExternal or keepVault")
                }
                val result = dev.svod.engine.sources.SourceSync(vc.engine, store).resolve(source, req.path, strategy)
                if (result.error != null) call.badRequest(result.error) else call.respond(result.toDto())
            }

            // ---- Recall memory: session capture + suggestions inbox (proposals) + dashboard ----

            post("/api/v1/memory/capture") {
                val vc = vault() ?: return@post call.notFound("vault")
                val req = call.receive<CaptureRequestDto>()
                // Idempotent on sessionId: a re-capture returns the existing note, never a duplicate.
                val existing = sessionMetas(vc).firstOrNull { it.sessionId == req.sessionId }
                if (existing != null) {
                    val rev = vc.engine.read(existing.path)?.revision ?: ""
                    return@post call.respond(CaptureResultDto(existing.path, rev, deduped = true))
                }
                val path = SessionNotes.pathFor(req.endedAt, req.project, req.sessionId)
                val note = SessionNotes.buildNote(req.project, req.sessionId, req.startedAt, req.endedAt, req.transcript)
                // writeBytes (not write) — raw transcripts routinely contain secrets; the capture store
                // keeps them verbatim and quarantines them from recall, so the write-path secret scanner
                // is deliberately bypassed here (it would otherwise Block a transcript carrying a token).
                when (val o = vc.engine.writeBytes(path, note.toByteArray(Charsets.UTF_8), null, config.uiAuthor)) {
                    is WriteOutcome.Success -> {
                        publishCommit(vc, o, "memory.capture")
                        call.respond(CaptureResultDto(o.path, o.revision, deduped = false))
                    }
                    is WriteOutcome.Conflict -> { publishConflict(vc, o.path); call.respond(HttpStatusCode.Conflict, o.toConflictDto()) }
                    is WriteOutcome.NotFound -> call.notFound(o.path)
                    is WriteOutcome.Blocked -> call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("blocked", o.findings.joinToString(", ")))
                }
            }

            get("/api/v1/memory/sessions") {
                val vc = vault() ?: return@get call.notFound("vault")
                val distilledFilter = call.request.queryParameters["distilled"]?.toBooleanStrictOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                var metas = sessionMetas(vc).sortedByDescending { it.endedAt }
                if (distilledFilter != null) metas = metas.filter { it.distilled == distilledFilter }
                if (limit != null) metas = metas.take(limit)
                call.respond(metas.map { SessionDto(it.path, it.project, it.sessionId, it.startedAt, it.endedAt, it.bytes, it.distilled) })
            }

            post("/api/v1/memory/sessions/mark-distilled") {
                val vc = vault() ?: return@post call.notFound("vault")
                val req = call.receive<MarkDistilledRequestDto>()
                var updated = 0
                for (p in req.paths) {
                    val fc = vc.engine.read(p) ?: continue
                    val next = SessionNotes.withDistilled(fc.text)
                    if (next == fc.text) continue
                    if (vc.engine.writeBytes(p, next.toByteArray(Charsets.UTF_8), fc.revision, config.uiAuthor) is WriteOutcome.Success) updated++
                }
                MemoryStore(vc.engine.root).recordDistill(req.noteRefs, System.currentTimeMillis())
                call.respond(MarkDistilledResultDto(updated))
            }

            get("/api/v1/memory/proposals") {
                val vc = vault() ?: return@get call.notFound("vault")
                val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() } ?: "open"
                call.respond(MemoryStore(vc.engine.root).proposals().filter { it.status == status }.map { it.toDto() })
            }

            post("/api/v1/memory/proposals") {
                val vc = vault() ?: return@post call.notFound("vault")
                val req = call.receive<CreateProposalRequestDto>()
                val p = MemoryStore(vc.engine.root).appendProposal(
                    req.kind, req.title, req.scope, req.confidence, req.rationale, req.sourceSessions, System.currentTimeMillis(),
                )
                call.respond(CreateProposalResultDto(p.id))
            }

            post("/api/v1/memory/proposals/{id}") {
                val vc = vault() ?: return@post call.notFound("vault")
                val id = call.parameters["id"] ?: return@post call.badRequest("missing proposal id")
                val req = call.receive<ProposalActionRequestDto>()
                val status = when (req.action) {
                    "accept" -> "accepted"; "reject" -> "rejected"
                    else -> return@post call.badRequest("action must be accept or reject")
                }
                // Status transition ONLY — accept does NOT create a skill/tool (suggestions over automation).
                val updated = MemoryStore(vc.engine.root).updateProposal(id, status, req.note)
                    ?: return@post call.notFound("proposal '$id'")
                call.respond(updated.toDto())
            }

            get("/api/v1/memory/dashboard") {
                val vc = vault() ?: return@get call.notFound("vault")
                call.respond(memoryDashboard(vc))
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

    /** The active embedder view for [vc] (control-provided when wired, else the read-only config). */
    private fun embedderInfo(vc: VaultView): EmbedderInfoDto {
        embedderControl?.descriptor(vc.id)?.let { return EmbedderInfoDto(it.provider, it.model, it.endpoint, it.dimension) }
        return EmbedderInfoDto(config.embedderProvider, vc.index.indexedModel() ?: config.embedderModel, config.embedderEndpoint, vc.index.indexedDim() ?: 0)
    }

    /** Per-vault metrics snapshot used to render the Prometheus exposition (built off the suspend API). */
    private data class VaultMetrics(
        val id: String, val docs: Int, val keywordReady: Boolean, val synced: Boolean,
        val embDone: Int, val embTotal: Int, val embState: String,
        val write: dev.svod.engine.obs.Metrics.WriteStats, val queue: Int, val queuePeak: Int, val conflicts: Int,
    )

    /**
     * Render all vaults' runtime metrics in the Prometheus text exposition format (hand-rolled — no
     * Micrometer dependency, and native-image-safe). Each per-vault series carries a `vault` label.
     */
    private suspend fun prometheusExposition(): String {
        val snaps = vaults.all().map { v ->
            val st = v.index.embeddingStatus()
            VaultMetrics(
                id = v.id, docs = v.index.docCount(), keywordReady = v.index.keywordReady(),
                synced = v.engine.head() == v.index.headCommitIndexed(),
                embDone = st.done, embTotal = st.total, embState = st.state.name.lowercase(),
                write = v.engine.metrics.snapshot(), queue = v.engine.queueDepth(),
                queuePeak = v.engine.peakQueueDepth(), conflicts = v.conflicts?.all()?.size ?: 0,
            )
        }
        val sb = StringBuilder()
        fun emit(name: String, type: String, help: String, samples: List<Pair<String, Number>>) {
            sb.append("# HELP ").append(name).append(' ').append(help).append('\n')
            sb.append("# TYPE ").append(name).append(' ').append(type).append('\n')
            for ((labels, value) in samples) sb.append(name).append(labels).append(' ').append(value).append('\n')
        }
        fun vlabel(id: String) = "{vault=\"${id.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"

        emit("svod_up", "gauge", "1 if the engine is serving.", listOf("" to 1))
        emit("svod_index_doc_count", "gauge", "Indexed Lucene documents (chunks) per vault.",
            snaps.map { vlabel(it.id) to it.docs })
        emit("svod_index_keyword_ready", "gauge", "1 when BM25/keyword search is consistent with HEAD.",
            snaps.map { vlabel(it.id) to if (it.keywordReady) 1 else 0 })
        emit("svod_index_synced", "gauge", "1 when the index HEAD matches the engine HEAD.",
            snaps.map { vlabel(it.id) to if (it.synced) 1 else 0 })
        emit("svod_embedding_done", "gauge", "Chunks embedded so far in the current/last pass.",
            snaps.map { vlabel(it.id) to it.embDone })
        emit("svod_embedding_total", "gauge", "Chunks targeted by the current/last embedding pass.",
            snaps.map { vlabel(it.id) to it.embTotal })
        emit("svod_embedding_state", "gauge", "1 for the embedding pass's current state (idle/running/paused/error).",
            snaps.flatMap { v ->
                IndexService.EmbeddingState.values().map { s ->
                    "{vault=\"${v.id}\",state=\"${s.name.lowercase()}\"}" to if (s.name.lowercase() == v.embState) 1 else 0
                }
            })
        emit("svod_write_total", "counter", "Total write-path mutations served.",
            snaps.map { vlabel(it.id) to it.write.count })
        emit("svod_write_latency_avg_ms", "gauge", "Average write-path latency (ms).",
            snaps.map { vlabel(it.id) to it.write.avgMs })
        emit("svod_write_latency_max_ms", "gauge", "Max write-path latency (ms).",
            snaps.map { vlabel(it.id) to it.write.maxMs })
        emit("svod_queue_depth", "gauge", "Current write-actor queue depth.",
            snaps.map { vlabel(it.id) to it.queue })
        emit("svod_queue_depth_peak", "gauge", "Peak write-actor queue depth.",
            snaps.map { vlabel(it.id) to it.queuePeak })
        emit("svod_conflicts", "gauge", "Open sync conflicts per vault.",
            snaps.map { vlabel(it.id) to it.conflicts })
        return sb.toString()
    }

    private fun indexStatus(vc: VaultView): IndexStatusDto {
        val s = vc.index.embeddingStatus()
        val provider = embedderInfo(vc).provider
        return IndexStatusDto(
            docCount = vc.index.docCount(),
            headIndexed = vc.index.headCommitIndexed(),
            model = vc.index.indexedModel() ?: "none",
            dim = vc.index.indexedDim() ?: 0,
            keywordReady = vc.index.keywordReady(),
            embedding = EmbeddingStatusDto(s.state.name.lowercase(), s.done, s.total, provider, s.model, s.error, s.ratePerSec, s.etaSeconds),
        )
    }

    private fun AgentSpecView.toDto() = AgentDto(agentId, name, role, vaults, tokenRef, prompt)

    /** All captured session notes' frontmatter for [vc] (bypasses the index — reads notes directly). */
    private suspend fun sessionMetas(vc: VaultView): List<SessionMeta> =
        vc.engine.list().filter { it.startsWith(SESSIONS_PREFIX) && it.endsWith(".md") }
            .mapNotNull { p -> vc.engine.read(p)?.let { SessionNotes.parseMeta(p, it.text) } }

    private fun Proposal.toDto() = ProposalDto(id, kind, title, scope, confidence, rationale, sourceSessions, createdAt, status, note)

    private suspend fun memoryDashboard(vc: VaultView): MemoryDashboardDto {
        val store = MemoryStore(vc.engine.root)
        val sessions = sessionMetas(vc)
        val capturedBytes = sessions.sumOf { it.bytes }
        // distilledBytes = current size of the curated notes the distiller wrote (note sizes only —
        // no generative call). compressionRatio guards divide-by-zero with max(1, distilledBytes).
        val noteRefs = store.distilledNoteRefs()
        val distilledBytes = noteRefs.sumOf { vc.engine.read(it)?.text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
        return MemoryDashboardDto(
            sessionsCaptured = sessions.size,
            sessionsDistilled = sessions.count { it.distilled },
            notesWritten = noteRefs.size,
            capturedBytes = capturedBytes,
            distilledBytes = distilledBytes,
            compressionRatio = capturedBytes.toDouble() / maxOf(1L, distilledBytes),
            lastDistillAt = store.lastDistillAt(),
            openProposals = store.proposals().count { it.status == "open" },
        )
    }

    private fun EmbedderRequestDto.toSpec() = EmbedderControl.EmbedderSpec(provider, model, endpoint, apiKeyRef, maxThreads)

    private fun encodeEvent(e: SvodEvent): String =
        jsonFormat.encodeToString(buildJsonObject { put("type", e.type); put("ts", e.ts); put("data", e.data) })

    private suspend fun RoutingContext.respondOutcome(vc: VaultView, outcome: WriteOutcome, tool: String) {
        when (outcome) {
            is WriteOutcome.Success -> { publishCommit(vc, outcome, tool); call.respond(WriteResultDto(outcome.path, outcome.revision, outcome.commit)) }
            is WriteOutcome.Conflict -> { publishConflict(vc, outcome.path); call.respond(HttpStatusCode.Conflict, outcome.toConflictDto()) }
            is WriteOutcome.NotFound -> call.notFound(outcome.path)
            is WriteOutcome.Blocked -> call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("blocked", "secret(s) detected: ${outcome.findings.joinToString(", ")}"))
        }
    }

    private fun publishCommit(vc: VaultView, s: WriteOutcome.Success, tool: String) {
        eventBus.publish(EventTypes.COMMIT_CREATED) { put("vault", vc.id); put("commit", s.commit); put("path", s.path); put("author", config.uiAuthor.name); put("tool", tool) }
        eventBus.publish(EventTypes.FILE_CHANGED) { put("vault", vc.id); put("path", s.path); put("source", "api") }
    }

    private fun publishConflict(vc: VaultView, path: String) =
        eventBus.publish(EventTypes.CONFLICT) { put("vault", vc.id); put("path", path); put("source", "api") }

    private fun WriteOutcome.Conflict.toConflictDto() = ConflictBodyDto(path, expected, current, currentContent)

    private fun dev.svod.engine.sources.ExternalSource.toDto(watching: Boolean = false) =
        ExternalSourceDto(id, path, into, followSymlinks, prune, autoSync, writeBack, watching, lastSyncedAt, conflicts)

    private fun dev.svod.engine.sources.SourceSyncResult.toDto() =
        SourceSyncResultDto(id, created, updated, unchanged, conflicts, orphaned, deleted, skipped, pushed, error)

    // Per-vault incremental link/tag index, owned by the server (one per vault, created on demand).
    // It catches up to HEAD by applying only the commit diff, so /file/links, /graph and /tags no
    // longer re-read and re-parse every note on each change. Closed when the server stops.
    private val linkIndexes = ConcurrentHashMap<String, dev.svod.engine.graph.LinkIndex>()
    private fun linkIndex(vc: VaultView): dev.svod.engine.graph.LinkIndex =
        linkIndexes.getOrPut(vc.id) { dev.svod.engine.graph.LinkIndex(vc.engine.root) }

    private fun graph(vc: VaultView): LinkGraph = linkIndex(vc).graph()
    private fun tags(vc: VaultView): List<TagCountDto> = linkIndex(vc).tagCounts().map { TagCountDto(it.tag, it.count) }

    // Qualified cross-vault wikilink: [[vault:target]] with optional #heading / |alias.
    private val qualifiedLink = Regex("""\[\[([^\[\]|#]+?):([^\[\]|#]+?)((?:#|\|)[^\[\]]*)?]]""")

    /**
     * Rewrite qualified `[[movedVault:from]]` references in every OTHER vault to point at `to`.
     * Each rewrite is a separate, optimistic commit in its own repo (D7) — best-effort: a failure
     * leaves the move intact and the stale link merely unresolved, never a lost file.
     */
    private suspend fun crossVaultRelink(movedVault: String, from: String, to: String) {
        val fromBase = from.substringAfterLast('/').removeSuffix(".md")
        val fromNorm = from.removeSuffix(".md")
        val toRef = if ('/' in to) to.removeSuffix(".md") else to.substringAfterLast('/').removeSuffix(".md")
        for (v in vaults.all()) {
            if (v.id == movedVault) continue
            for (path in v.engine.list()) {
                if (!path.endsWith(".md")) continue
                val fc = v.engine.read(path) ?: continue
                val updated = qualifiedLink.replace(fc.text) { m ->
                    val tgtVault = m.groupValues[1].trim()
                    val tgt = m.groupValues[2].trim().removeSuffix(".md")
                    val suffix = m.groupValues[3]
                    if (tgtVault == movedVault && (tgt == fromNorm || tgt.substringAfterLast('/') == fromBase))
                        "[[$movedVault:$toRef$suffix]]" else m.value
                }
                if (updated != fc.text) runCatching { v.engine.write(path, updated, fc.revision, config.uiAuthor) }
            }
        }
    }

    // The cross-vault link graph, cached by the combined HEADs of every vault.
    @Volatile private var fedCache: Pair<String, dev.svod.engine.graph.FederatedLinkGraph>? = null
    private val fedMutex = kotlinx.coroutines.sync.Mutex()
    private suspend fun federated(): dev.svod.engine.graph.FederatedLinkGraph {
        val keyParts = StringBuilder()
        val data = LinkedHashMap<String, Map<String, List<String>>>()
        for (v in vaults.all()) {
            keyParts.append(v.id).append('@').append(v.engine.head()).append('|')
            data[v.id] = linkIndex(v).targetsByPath() // cached per-note targets, caught up to HEAD
        }
        val key = keyParts.toString()
        fedCache?.let { if (it.first == key) return it.second }
        return fedMutex.withLock {
            fedCache?.let { if (it.first == key) return@withLock it.second } // re-check under lock
            dev.svod.engine.graph.FederatedLinkGraph.buildFromTargets(data).also { fedCache = key to it }
        }
    }

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

private suspend fun io.ktor.server.application.ApplicationCall.notImplemented(what: String) =
    respond(HttpStatusCode.NotImplemented, ErrorDto("not_implemented", "$what is not available in this build"))
