package dev.svod.engine.teamsharing

import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.lifecycle.SvodNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.X509TrustManager

internal val trustAll = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal data class Person(val id: String, val name: String, val email: String)

/** [grant] is the explicit `{vault, role}` role, or null for a bare vault id (⇒ writer for a bound agent). */
internal data class TestAgent(val id: String, val person: Person, val grant: String?, val readOnlyCeiling: Boolean = false) {
    /** Role after ADR-0020 Decision 2: min(grant, ceiling). */
    val effective: String = when {
        readOnlyCeiling -> "reader"
        else -> grant ?: "writer"
    }
    val mayWrite get() = effective != "reader"
    val mayPromote get() = effective == "maintainer"
}

/** One tool result: the JSON `status` plus the MCP `isError` flag. */
internal class Res(val isError: Boolean, val text: String) {
    val json: JsonObject by lazy { runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) } }
    val status: String? get() = str("status")
    fun str(key: String): String? = json[key]?.jsonPrimitive?.contentOrNull
}

/** A connected MCP client for one agent, over HTTPS with the agent's bearer token. */
internal class McpAgent(val def: TestAgent, private val port: Int, token: String, private val rateLimited: AtomicInteger) {
    private val http = HttpClient(ClientCIO) {
        install(SSE)
        install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
        engine { https { trustManager = trustAll } }
    }
    private val client = Client(Implementation("team-sharing-qa-${def.id}", "1.0.0"))

    suspend fun open(): McpAgent {
        client.connect(StreamableHttpClientTransport(client = http, url = "https://127.0.0.1:$port/mcp"))
        return this
    }

    /** Calls a tool; a `rate_limited` answer (the engine's per-agent token bucket) is waited out and counted. */
    suspend fun call(tool: String, args: Map<String, Any?>): Res {
        while (true) {
            val r = client.callTool(tool, args)
            val res = Res(r.isError == true, (r.content.firstOrNull { it is TextContent } as? TextContent)?.text.orEmpty())
            if (res.status == "rate_limited") { rateLimited.incrementAndGet(); delay(120); continue }
            return res
        }
    }

    suspend fun close() {
        runCatching { client.close() }
        http.close()
    }
}

/**
 * One real engine ([SvodNode]) with one team vault `team`, MCP over a self-signed HTTPS endpoint on an
 * ephemeral port, three people and seven agents (ADR-0020). Temp dirs only.
 */
internal class TeamFixture : AutoCloseable {
    val root: Path = Files.createTempDirectory("svod-team-sharing-")
    val vaultDir: Path = Files.createDirectories(root.resolve("team-vault"))
    val rateLimited = AtomicInteger()

    val alice = Person("alice", "Alice Anders", "alice@team.test")
    val bob = Person("bob", "Bob Brandt", "bob@team.test")
    val carol = Person("carol", "Carol Chen", "carol@team.test")

    val agents = listOf(
        TestAgent("alice-1", alice, "maintainer"),
        TestAgent("alice-2", alice, "maintainer"),
        TestAgent("bob-1", bob, null),
        TestAgent("bob-2", bob, null),
        TestAgent("carol-reader", carol, "reader"),
        TestAgent("carol-ceiling", carol, "maintainer", readOnlyCeiling = true),
    )
    val writers = agents.filter { it.mayWrite }

    private val tokens: Map<String, String>
    val node: SvodNode
    val port: Int get() = node.mcpPort

    init {
        val ks = root.resolve("mcp.p12")
        buildKeyStore {
            certificate("svod") { password = "changeit"; domains = listOf("127.0.0.1", "localhost") }
        }.saveToFile(ks.toFile(), "changeit")

        val keys = Files.createDirectories(root.resolve("secrets"))
        fun secretFile(name: String): String {
            val f = keys.resolve(name)
            Files.writeString(f, "secret-${name}-${java.util.UUID.randomUUID()}")
            return "file:$f"
        }
        val tokenRefs = agents.associate { it.id to secretFile("agent-${it.id}") }
        tokens = tokenRefs.mapValues { (_, ref) -> Files.readString(Path.of(ref.removePrefix("file:"))).trim() }

        node = SvodNode.start(SvodConfig(
            vaults = listOf(SvodConfig.VaultSettings("team", vaultDir.toString())),
            appApiPort = 0, mcpPort = 0,
            embedder = SvodConfig.EmbedderSettings(provider = "none"),
            reranker = SvodConfig.RerankerSettings(provider = "none"),
            mcpTls = SvodConfig.TlsSettings(ks.toString(), "changeit", "svod", "changeit"),
            users = listOf(alice, bob, carol).map { SvodConfig.UserSettings(it.id, it.name, it.email, secretFile("user-${it.id}")) },
            agents = agents.map { a ->
                SvodConfig.AgentSettings(
                    token = tokenRefs.getValue(a.id), agentId = a.id,
                    role = if (a.readOnlyCeiling) "READ_ONLY" else "WRITE",
                    vaults = listOf("team"), user = a.person.id,
                    vaultRoles = a.grant?.let { mapOf("team" to it) } ?: emptyMap(),
                )
            },
        ))
    }

    fun token(agent: TestAgent): String = tokens.getValue(agent.id)

    suspend fun connect(agent: TestAgent): McpAgent = McpAgent(agent, port, token(agent), rateLimited).open()

    // --- git helpers (jgit reads the vault repo directly; the engine is never bypassed for writes) ---

    fun head(): String? = Git.open(vaultDir.toFile()).use { it.repository.resolve("HEAD")?.name }

    fun commits(): List<RevCommit> = Git.open(vaultDir.toFile()).use { g ->
        if (g.repository.resolve("HEAD") == null) emptyList() else g.log().call().toList()
    }

    /** `git fsck --full --strict` with the native binary; returns its combined output and exit code. */
    fun fsck(): Pair<Int, String> {
        val p = ProcessBuilder("git", "-C", vaultDir.toString(), "fsck", "--full", "--strict").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    override fun close() {
        runCatching { node.shutdown() }
        runCatching { root.toFile().deleteRecursively() }
    }
}
