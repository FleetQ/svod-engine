package dev.svod.engine.api

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.mcp.AgentIdentity
import dev.svod.engine.mcp.AgentRegistry
import dev.svod.engine.mcp.AgentRole
import dev.svod.engine.mcp.AuditLog
import dev.svod.engine.mcp.RateLimiter
import dev.svod.engine.mcp.SvodTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/** Wires engine + index + event bus + App API (+ MCP tools sharing the bus) on a temp vault. */
class ApiFixture : AutoCloseable {
    val root: Path = Files.createTempDirectory("svod-api-test-")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine: SvodEngine = SvodEngine.open(root, scope)
    val index: IndexService = IndexService(root, root.resolve(".svod").resolve("index"), NoneEmbedder).start()
    val eventBus = EventBus()
    val audit = AuditLog(root.resolve(".svod").resolve("audit").resolve("audit.log"))
    val registry = AgentRegistry(listOf(AgentRegistry.AgentSpec("w", "scribe", AgentRole.WRITE, name = "Scribe")))
    val tools = SvodTools(engine, index, audit, RateLimiter.default(), eventBus)
    private val api = AppApiServer(engine, index, eventBus)
    private val running = api.start(0)

    val port: Int get() = running.port
    val writeAgent: AgentIdentity get() = registry.byAgentId("scribe")!!

    init {
        engine.onCommit { index.onCommit(it) }
    }

    private val http: HttpClient = HttpClient.newHttpClient()

    fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    fun put(path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    fun post(path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    fun delete(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).DELETE().build(), HttpResponse.BodyHandlers.ofString())

    override fun close() {
        running.stop()
        index.close()
        engine.close()
    }

    companion object {
        fun create(): ApiFixture = ApiFixture()
        fun enc(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)
    }
}
