package dev.svod.engine.mcp

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.NoneEmbedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path

val WRITE_AGENT = AgentRegistry.AgentSpec("write-token", "scribe", AgentRole.WRITE, name = "Scribe", email = "scribe@agents.test")
val READ_AGENT = AgentRegistry.AgentSpec("read-token", "reader", AgentRole.READ_ONLY, name = "Reader", email = "reader@agents.test")

/** A temp vault wired up with engine + (BM25-only) index + audit + tools, for MCP tests. */
class McpFixture(
    rateLimiter: RateLimiter = RateLimiter.default(),
    agents: List<AgentRegistry.AgentSpec> = listOf(WRITE_AGENT, READ_AGENT),
) : AutoCloseable {
    val root: Path = Files.createTempDirectory("svod-mcp-test-")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine: SvodEngine = SvodEngine.open(root, scope)
    val index: IndexService = IndexService(root, root.resolve(".svod").resolve("index"), NoneEmbedder).start()
    val audit: AuditLog = AuditLog(root.resolve(".svod").resolve("audit").resolve("audit.log"))
    val registry: AgentRegistry = AgentRegistry(agents)
    val tools: SvodTools = SvodTools(engine, index, audit, rateLimiter)

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
