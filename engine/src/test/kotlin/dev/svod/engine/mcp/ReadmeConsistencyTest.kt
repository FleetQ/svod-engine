package dev.svod.engine.mcp

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps README.md honest: every fact it states about the tool list, the contract version, the
 * engine version, the ADR range and the App API paths is read from the code and compared here.
 */
class ReadmeConsistencyTest {

    private val engineDir: Path = Paths.get(System.getProperty("svod.projectDir") ?: System.getProperty("user.dir"))
    private val repoRoot: Path = engineDir.toAbsolutePath().parent
    private val readme: String = Files.readString(repoRoot.resolve("README.md"))
    private val openapi: String = Files.readString(repoRoot.resolve("contract/openapi.yaml"))

    /** The README text from the `## <heading>` line up to the next `## ` heading. */
    private fun section(heading: String): String {
        val lines = readme.lines()
        val start = lines.indexOfFirst { it.startsWith("## ") && it.removePrefix("## ").trim().startsWith(heading) }
        assertTrue(start >= 0, "README has no '## $heading' section")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun advertisedTools(): List<String> = ADVERTISED_TOOLS

    private companion object {
        fun meta(): String =
            """"_meta":{"${McpProtocol.META_PROTOCOL_VERSION}":"${McpProtocol.V2026_07_28}",""" +
                """"${McpProtocol.META_CLIENT_INFO}":{"name":"readme-test","version":"1.0.0"},""" +
                """"${McpProtocol.META_CLIENT_CAPABILITIES}":{}}"""

        /** The tool names a WRITE agent sees over a stateless tools/list, fetched once per test class. */
        val ADVERTISED_TOOLS: List<String> by lazy { fetchAdvertisedTools() }

        fun fetchAdvertisedTools(): List<String> = runBlocking {
            McpFixture().use { fx ->
                val server = SvodMcpServer(fx.tools, fx.registry).start(0)
                try {
                    HttpClient(ClientCIO).use { http ->
                        val res = http.post("http://127.0.0.1:${server.port}/mcp") {
                            header(HttpHeaders.Authorization, "Bearer ${WRITE_AGENT.token}")
                            header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.V2026_07_28)
                            header(McpProtocol.HEADER_METHOD, "tools/list")
                            contentType(ContentType.Application.Json)
                            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{${meta()}}}""")
                        }
                        Json.parseToJsonElement(res.bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
                            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                    }
                } finally { server.stop() }
            }
        }
    }

    @Test
    fun `every tool count in the README equals the advertised tool list`() {
        val count = advertisedTools().size
        // `[\s-]` also catches the "Jump to" anchor slug (`#the-agent-interface--20-mcp-tools`)
        val matches = Regex("""(\d+)(?:[\s-]+MCP)?[\s-]+tools\b""", RegexOption.IGNORE_CASE).findAll(readme).toList()
        assertTrue(matches.isNotEmpty(), "README states no MCP tool count")
        val wrong = matches.filter { it.groupValues[1].toInt() != count }.map { m ->
            val line = readme.substring(0, m.range.first).count { it == '\n' } + 1
            "line $line: '${m.value}'"
        }
        assertTrue(wrong.isEmpty(), "README tool counts must be $count (tools/list): $wrong")
    }

    @Test
    fun `the agent interface section lists exactly the advertised tool names`() {
        val text = section("The agent interface")
        fun namesOn(label: String): Set<String> {
            val line = text.lines().firstOrNull { it.startsWith(label) }
            assertTrue(line != null, "'## The agent interface' has no line starting with $label")
            // the list may wrap onto continuation lines until the next blank or bold label line
            val lines = text.lines()
            val i = lines.indexOf(line)
            val block = buildList {
                add(line.removePrefix(label))
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() && !lines[j].startsWith("**")) { add(lines[j]); j++ }
            }.joinToString(" ")
            return Regex("`([a-z0-9_]+)`").findAll(block).map { it.groupValues[1] }.toSet()
        }
        val listed = namesOn("**Read (any role):**") + namesOn("**Write (WRITE role):**")
        val advertised = advertisedTools().toSet()
        assertEquals(
            advertised.sorted(), listed.sorted(),
            "README tool names differ from tools/list: missing ${advertised - listed}, extra ${listed - advertised}",
        )
    }

    @Test
    fun `every contract version in the README equals openapi info version`() {
        // VersionConsistencyTest pins this constant to openapi.yaml info.version
        val version = dev.svod.engine.lifecycle.ApiCompatibility.CURRENT_CONTRACT_VERSION
        val matches = Regex("""contract \*{0,2}(\d+\.\d+\.\d+)""").findAll(readme).toList() +
            Regex("""currently \*{0,2}(\d+\.\d+\.\d+)""").findAll(readme).toList()
        assertTrue(matches.isNotEmpty(), "README states no contract version")
        val wrong = matches.filter { it.groupValues[1] != version }.map { m ->
            val line = readme.substring(0, m.range.first).count { it == '\n' } + 1
            "line $line: '${m.value}'"
        }
        assertTrue(wrong.isEmpty(), "README contract versions must be $version (openapi info.version): $wrong")
    }

    @Test
    fun `the Status section names the current engine version`() {
        // VersionConsistencyTest pins this constant to the Gradle version
        val version = dev.svod.engine.lifecycle.SvodNode.ENGINE_VERSION
        val first = section("Status").lines().drop(1).first { it.isNotBlank() }
        assertEquals(
            version, Regex("""^\*\*v(\S+)""").find(first)?.groupValues?.get(1),
            "README '## Status' must start with **v$version (engine version), starts with: '$first'",
        )
    }

    @Test
    fun `the Status section's ADR range ends at the newest ADR`() {
        val newest = Files.list(repoRoot.resolve("docs/adr")).use { files ->
            files.toList().mapNotNull { Regex("""^(\d{4})-.*\.md$""").find(it.fileName.toString())?.groupValues?.get(1) }.max()
        }
        val status = section("Status")
        assertTrue(
            "ADR-0001 through $newest" in status,
            "README '## Status' must say 'ADR-0001 through $newest' (newest file in docs/adr), found: " +
                Regex("""ADR-0001 through \d+""").find(status)?.value,
        )
    }

    @Test
    fun `every contract path is named in the App API section`() {
        val paths = Regex("""(?m)^  (/\S+):$""").findAll(openapi).map { it.groupValues[1] }.toList()
        assertTrue(paths.isNotEmpty(), "no paths parsed from contract/openapi.yaml")
        val text = section("The UI interface — local App API")
        val missing = paths.filter { p ->
            val short = p.removePrefix("/api/v1/")
            "`$p`" !in text && "`$short`" !in text
        }
        assertTrue(missing.isEmpty(), "README '## The UI interface — local App API' does not name these contract paths: $missing")
    }

    @Test
    fun `the feature tour has a team-sharing row linked to its ADR`() {
        val tour = section("What it does (feature tour)")
        assertTrue(
            "](docs/adr/0020-team-sharing-users-and-roles.md)" in tour,
            "README feature tour has no row linking docs/adr/0020-team-sharing-users-and-roles.md",
        )
    }
}
