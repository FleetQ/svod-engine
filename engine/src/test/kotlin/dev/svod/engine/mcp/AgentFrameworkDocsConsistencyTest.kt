package dev.svod.engine.mcp

import dev.svod.engine.lifecycle.SvodConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps docs/integrations-agent-frameworks.md honest: the tool names its snippets call, the
 * arguments they pass, the MCP URL and the auth header are compared with the live tools/list and
 * the SvodConfig defaults.
 */
class AgentFrameworkDocsConsistencyTest {

    private val engineDir: Path = Paths.get(System.getProperty("svod.projectDir") ?: System.getProperty("user.dir"))
    private val repoRoot: Path = engineDir.toAbsolutePath().parent
    private val pagePath: Path = repoRoot.resolve(PAGE)

    private val page: String by lazy {
        assertTrue(Files.exists(pagePath), "$PAGE does not exist")
        Files.readString(pagePath)
    }

    private val defaults = SvodConfig(vaultPath = "x")

    /** A fenced code block: its language tag, its body and the 1-based line its fence opens on. */
    private data class Block(val lang: String, val body: String, val line: Int)

    private fun lineOf(text: String, offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1

    private fun blocks(text: String): List<Block> =
        FENCE.findAll(text).map { Block(it.groupValues[1], it.groupValues[2], lineOf(page, page.indexOf(it.value))) }.toList()

    /** The page text from the `## <heading>` line up to the next `## ` heading. */
    private fun section(heading: String): String {
        val lines = page.lines()
        val start = lines.indexOfFirst { it.startsWith("## ") && it.removePrefix("## ").trim().startsWith(heading) }
        assertTrue(start >= 0, "$PAGE has no '## $heading' section")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    /** Tool names called in [code], with the `svod_` server prefix (Mastra) removed. */
    private fun calledTools(code: String): Set<String> =
        CALL_PATTERNS.flatMap { p -> p.findAll(code).map { it.groupValues[1].removePrefix("svod_") } }.toSet()

    private companion object {
        const val PAGE = "docs/integrations-agent-frameworks.md"

        /** Section heading prefix -> the language its snippet must be tagged with. */
        val FRAMEWORKS = linkedMapOf(
            "LangGraph" to "python",
            "OpenAI Agents SDK" to "python",
            "Vercel AI SDK" to "ts",
            "Mastra" to "ts",
        )

        val FENCE = Regex("""(?ms)^```(\w*)\n(.*?)^```""")

        /** The ways a snippet calls a tool: call_tool("x"), tools["x"] / tools_by_name["x"], tools.x.execute. */
        val CALL_PATTERNS = listOf(
            Regex("""call_tool\(\s*["']([a-z_]+)["']"""),
            Regex("""tools(?:_by_name)?\[\s*["']([a-z_]+)["']\s*]"""),
            Regex("""tools\.([a-z_]+)\.execute\b"""),
        )

        val URL = Regex("""https?://(?:127\.0\.0\.1|localhost):(\d+)([^\s"'`)<>]*)""")

        /** Name -> inputSchema.required of every tool a WRITE agent sees over a stateless tools/list. */
        val TOOLS: Map<String, List<String>> by lazy { fetchToolSchemas() }

        fun fetchToolSchemas(): Map<String, List<String>> = runBlocking {
            McpFixture().use { fx ->
                val server = SvodMcpServer(fx.tools, fx.registry).start(0)
                try {
                    HttpClient(ClientCIO).use { http ->
                        val meta = """"_meta":{"${McpProtocol.META_PROTOCOL_VERSION}":"${McpProtocol.V2026_07_28}",""" +
                            """"${McpProtocol.META_CLIENT_INFO}":{"name":"docs-test","version":"1.0.0"},""" +
                            """"${McpProtocol.META_CLIENT_CAPABILITIES}":{}}"""
                        val res = http.post("http://127.0.0.1:${server.port}/mcp") {
                            header(HttpHeaders.Authorization, "Bearer ${WRITE_AGENT.token}")
                            header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.V2026_07_28)
                            header(McpProtocol.HEADER_METHOD, "tools/list")
                            contentType(ContentType.Application.Json)
                            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{$meta}}""")
                        }
                        Json.parseToJsonElement(res.bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
                            .associate { t ->
                                val o = t.jsonObject
                                val required = (o["inputSchema"]?.jsonObject?.get("required") as? JsonArray)
                                    ?.map { it.jsonPrimitive.content }.orEmpty()
                                o["name"]!!.jsonPrimitive.content to required
                            }
                    }
                } finally { server.stop() }
            }
        }
    }

    @Test
    fun `the page exists and has a code block for each framework`() {
        FRAMEWORKS.forEach { (heading, lang) ->
            val code = blocks(section(heading))
            assertTrue(code.isNotEmpty(), "$PAGE '## $heading' has no fenced code block")
            assertTrue(
                code.any { it.lang == lang },
                "$PAGE '## $heading' has no ```$lang block (found: ${code.map { "line ${it.line}: ```${it.lang}" }})",
            )
        }
    }

    @Test
    fun `every MCP URL uses the default MCP port and the mcp path`() {
        val urls = URL.findAll(page).toList()
        assertTrue(urls.isNotEmpty(), "$PAGE shows no http://127.0.0.1:<port> URL")
        val wrong = urls.filter { it.groupValues[1].toInt() != defaults.mcpPort || it.groupValues[2] != "/mcp" }
            .map { "line ${lineOf(page, it.range.first)}: '${it.value}'" }
        assertTrue(
            wrong.isEmpty(),
            "$PAGE URLs must be http://127.0.0.1:${defaults.mcpPort}/mcp (MCP, not the App API port ${defaults.appApiPort}): $wrong",
        )
    }

    @Test
    fun `every framework snippet sends a bearer Authorization header`() {
        FRAMEWORKS.keys.forEach { heading ->
            val code = blocks(section(heading)).joinToString("\n") { it.body }
            assertTrue("Authorization" in code, "$PAGE '## $heading' code sends no Authorization header")
            assertTrue("Bearer " in code, "$PAGE '## $heading' code has no 'Bearer ' token prefix")
        }
    }

    @Test
    fun `every tool the page names is advertised by tools-list`() {
        val advertised = TOOLS.keys
        // The tool is the first backticked name of each list item; later ones are its arguments or values.
        val listed = Regex("""(?m)^- `([a-z0-9_]+)`""").findAll(section("Tools used")).map { it.groupValues[1] }.toSet()
        assertTrue(listed.isNotEmpty(), "$PAGE '## Tools used' names no backticked tool")
        assertTrue((listed - advertised).isEmpty(), "$PAGE '## Tools used' names tools not in tools/list: ${listed - advertised}")
        blocks(page).forEach { b ->
            val unknown = calledTools(b.body) - advertised
            assertTrue(unknown.isEmpty(), "$PAGE code block at line ${b.line} calls tools not in tools/list: $unknown")
        }
        FRAMEWORKS.keys.forEach { heading ->
            val called = blocks(section(heading)).flatMap { calledTools(it.body) }.toSet()
            assertTrue("search" in called && "remember" in called, "$PAGE '## $heading' must call search and remember, calls: $called")
        }
    }

    @Test
    fun `the snippets pass every required argument of the tools they call`() {
        val problems = blocks(page).flatMap { b ->
            calledTools(b.body).flatMap { tool ->
                TOOLS[tool].orEmpty().filterNot { arg ->
                    Regex("""(?<![\w])["']?${Regex.escape(arg)}["']?\s*[:=]""").containsMatchIn(b.body)
                }.map { "line ${b.line}: $tool needs '$it'" }
            }
        }
        assertTrue(problems.isEmpty(), "$PAGE snippets miss required tool arguments: $problems")
    }

    @Test
    fun `any tool count on the page equals tools-list`() {
        val count = TOOLS.size
        val wrong = Regex("""(\d+)(?:[\s-]+MCP)?[\s-]+tools\b""", RegexOption.IGNORE_CASE).findAll(page)
            .filter { it.groupValues[1].toInt() != count }
            .map { "line ${lineOf(page, it.range.first)}: '${it.value}'" }.toList()
        assertTrue(wrong.isEmpty(), "$PAGE tool counts must be $count (tools/list): $wrong")
    }

    @Test
    fun `examples README links the page`() {
        val examples = Files.readString(repoRoot.resolve("examples/README.md"))
        assertTrue("](../$PAGE)" in examples, "examples/README.md does not link ../$PAGE")
    }
}
