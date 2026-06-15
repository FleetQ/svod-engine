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
    /** Runtime embedder control; null ⇒ PUT /embedder & POST /embedder/test return 501. */
    private val embedderControl: EmbedderControl? = null,
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
    ) : this(SingleVaultRouter(svod, index, conflicts, syncStatus), eventBus, config, readiness)

    data class Config(
        val host: String = "127.0.0.1",
        val apiVersion: String = "0.10.0",
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
                val max = call.request.queryParameters["max"]?.toIntOrNull() ?: 50
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
                val q = call.request.queryParameters["q"] ?: return@get call.badRequest("missing q")
                val mode = when (call.request.queryParameters["mode"]?.lowercase()) {
                    "keyword" -> SearchMode.KEYWORD; "semantic" -> SearchMode.SEMANTIC; else -> SearchMode.HYBRID
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                val filters = SearchFilters(
                    tags = call.request.queryParameters.getAll("tags") ?: emptyList(),
                    pathPrefix = call.request.queryParameters["pathPrefix"],
                )
                val across = call.request.queryParameters["across"]?.equals("true", ignoreCase = true) == true
                if (across) {
                    // Federated: query every vault, tag each hit with its vault, merge by score.
                    val hits = ArrayList<SearchHitDto>()
                    for (v in vaults.all()) {
                        val r = v.index.search(SearchQuery(q, filters, mode, limit))
                        r.hits.forEach { hits.add(SearchHitDto(it.path, it.heading, it.snippet, it.score, it.matchedKeyword, it.matchedSemantic, it.tags, v.id)) }
                    }
                    hits.sortByDescending { it.score }
                    call.respond(SearchResultDto(mode.name, hits.take(limit)))
                } else {
                    val result = vc.index.search(SearchQuery(q, filters, mode, limit))
                    call.respond(SearchResultDto(result.mode.name, result.hits.map {
                        SearchHitDto(it.path, it.heading, it.snippet, it.score, it.matchedKeyword, it.matchedSemantic, it.tags, vc.id)
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
                // No peers ⇒ nothing to reconcile: a successful no-op (ok=false), not an error.
                if (syncConfig(vc).syncPeers.isEmpty()) {
                    return@post call.respond(SyncAckDto(ok = false, head = vc.engine.head(), conflicts = 0))
                }
                // A transport failure (peer unreachable / auth) is an expected outcome → 409, never 500.
                try {
                    vaults.syncNow(vc.id)
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.Conflict, ErrorDto(
                        "sync_failed",
                        "sync with peers failed — check the remotes are reachable and their credentials resolve",
                    ))
                }
                val status = vc.syncStatus()
                call.respond(SyncAckDto(ok = true, head = status?.lastHead ?: vc.engine.head(), conflicts = status?.conflicts ?: 0))
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
                    "noop" -> call.respond(BackupAckDto(ok = false, head = r.head))
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
                // Set + persist THIS vault's backup remote (survives restart via its BackupConfigStore),
                // then return the vault's updated, redacted sync+backup config.
                backup?.configure(vc.id, dev.svod.engine.lifecycle.SvodConfig.BackupSettings(req.remote, req.enabled))
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
                call.respond(store.list().map { it.toDto() })
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
                )
                call.respond(store.put(source).toDto())
            }

            delete("/api/v1/sources/{id}") {
                val vc = vault() ?: return@delete call.notFound("vault")
                val id = call.parameters["id"] ?: return@delete call.badRequest("missing source id")
                val store = dev.svod.engine.sources.ExternalSourceStore(vc.engine.root)
                if (store.remove(id)) call.respond(HttpStatusCode.NoContent) else call.notFound("source '$id'")
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

    private fun dev.svod.engine.sources.ExternalSource.toDto() =
        ExternalSourceDto(id, path, into, followSymlinks, prune, lastSyncedAt)

    private fun dev.svod.engine.sources.SourceSyncResult.toDto() =
        SourceSyncResultDto(id, created, updated, unchanged, conflicts, orphaned, deleted, skipped, error)

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
