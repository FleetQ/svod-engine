package dev.svod.engine.mcp

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

val WRITE_AGENT = AgentRegistry.AgentSpec("write-token", "scribe", AgentRole.WRITE, name = "Scribe", email = "scribe@agents.test")
val READ_AGENT = AgentRegistry.AgentSpec("read-token", "reader", AgentRole.READ_ONLY, name = "Reader", email = "reader@agents.test")

/** The tool names a WRITE agent sees over a stateless tools/list on a fresh in-process MCP server. */
internal fun fetchAdvertisedToolNames(): List<String> = runBlocking {
    McpFixture().use { fx ->
        val server = SvodMcpServer(fx.tools, fx.registry).start(0)
        try {
            HttpClient(ClientCIO).use { http ->
                val meta = """"_meta":{"${McpProtocol.META_PROTOCOL_VERSION}":"${McpProtocol.V2026_07_28}",""" +
                    """"${McpProtocol.META_CLIENT_INFO}":{"name":"readme-test","version":"1.0.0"},""" +
                    """"${McpProtocol.META_CLIENT_CAPABILITIES}":{}}"""
                val res = http.post("http://127.0.0.1:${server.port}/mcp") {
                    header(HttpHeaders.Authorization, "Bearer ${WRITE_AGENT.token}")
                    header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.V2026_07_28)
                    header(McpProtocol.HEADER_METHOD, "tools/list")
                    contentType(ContentType.Application.Json)
                    setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{$meta}}""")
                }
                Json.parseToJsonElement(res.bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
                    .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            }
        } finally { server.stop() }
    }
}

/** A temp vault wired up with engine + (BM25-only) index + audit + tools, for MCP tests. */
class McpFixture(
    rateLimiter: RateLimiter = RateLimiter.default(),
    agents: List<AgentRegistry.AgentSpec> = listOf(WRITE_AGENT, READ_AGENT),
    /** The vault id the tools stamp onto their events; null = tools not bound to a vault. */
    vaultId: String? = null,
    embedder: dev.svod.engine.index.Embedder = NoneEmbedder,
) : AutoCloseable {
    val root: Path = Files.createTempDirectory("svod-mcp-test-")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine: SvodEngine = SvodEngine.open(root, scope)
    val index: IndexService = IndexService(root, root.resolve(".svod").resolve("index"), embedder).start()
    val audit: AuditLog = AuditLog(root.resolve(".svod").resolve("audit").resolve("audit.log"))
    val registry: AgentRegistry = AgentRegistry(agents)
    val eventBus: dev.svod.engine.events.EventBus = dev.svod.engine.events.EventBus()
    val tools: SvodTools = SvodTools(engine, index, audit, rateLimiter, eventBus, vaultId = vaultId)

    init {
        engine.onCommit { index.onCommit(it) }
    }

    val write: AgentIdentity get() = registry.byAgentId("scribe")!!
    val read: AgentIdentity get() = registry.byAgentId("reader")!!

    override fun close() {
        index.close()
        engine.close()
    }
}
