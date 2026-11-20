package dev.svod.engine.mcp

import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.lifecycle.SvodNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
 * Keeps the MCP Registry manifest (`server.json` at the repo root) in line with the engine and README:
 * version, endpoint, auth header, identity fields and any stated tool count.
 */
class McpDirectoryManifestTest {

    private val engineDir: Path = Paths.get(System.getProperty("svod.projectDir") ?: System.getProperty("user.dir"))
    private val repoRoot: Path = engineDir.toAbsolutePath().parent
    private val manifestPath: Path = repoRoot.resolve("server.json")

    private fun manifestText(): String {
        assertTrue(Files.exists(manifestPath), "server.json (MCP Registry manifest) is missing at the repo root")
        return Files.readString(manifestPath)
    }

    private fun manifest(): JsonObject = Json.parseToJsonElement(manifestText()).jsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    /** The README text from the `## <heading>` line up to the next `## ` heading. */
    private fun section(heading: String): String {
        val lines = Files.readString(repoRoot.resolve("README.md")).lines()
        val start = lines.indexOfFirst { it.startsWith("## ") && it.removePrefix("## ").trim().startsWith(heading) }
        assertTrue(start >= 0, "README has no '## $heading' section")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    @Test
    fun `server json exists and its version equals the engine version`() {
        assertEquals(
            SvodNode.ENGINE_VERSION, manifest().str("version"),
            "server.json version must equal SvodNode.ENGINE_VERSION — bump server.json when bumping the engine version",
        )
    }

    @Test
    fun `the streamable-http remote points at the default MCP endpoint with a bearer header`() {
        val remotes = manifest()["remotes"]?.jsonArray
        assertTrue(remotes != null && remotes.isNotEmpty(), "server.json has no remotes entry")
        val remote = remotes[0].jsonObject
        assertEquals("streamable-http", remote.str("type"))
        assertEquals("http://127.0.0.1:${SvodConfig(vaultPath = "/tmp/x").mcpPort}/mcp", remote.str("url"))
        val auth = remote["headers"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("name") == "Authorization" }
        assertTrue(auth != null, "server.json remote has no Authorization header")
        assertTrue(auth.str("description").orEmpty().startsWith("Bearer"), "Authorization header description must describe a Bearer token")
        assertEquals(true, auth["isRequired"]?.jsonPrimitive?.boolean, "Authorization header must be isRequired")
        assertEquals(true, auth["isSecret"]?.jsonPrimitive?.boolean, "Authorization header must be isSecret")
        assertTrue(auth["value"] == null && auth["default"] == null, "server.json must not carry a token value")
    }

    @Test
    fun `identity fields match the repo`() {
        val m = manifest()
        assertEquals("io.github.FleetQ/svod-engine", m.str("name"))
        val repo = m["repository"]?.jsonObject
        assertEquals("https://github.com/FleetQ/svod-engine", repo?.str("url"))
        assertEquals("github", repo?.str("source"))
        val description = m.str("description").orEmpty()
        assertTrue(description.isNotBlank(), "server.json description is blank")
        assertTrue(description.length <= 100, "server.json description must be at most 100 characters, is ${description.length}")
        assertTrue(m.str("\$schema").orEmpty().startsWith("https://static.modelcontextprotocol.io/schemas/"), "server.json \$schema is not the MCP Registry schema")
        assertTrue(Files.readString(repoRoot.resolve("LICENSE")).contains("Apache License"), "LICENSE is no longer Apache-2.0; review server.json and README")
    }

    @Test
    fun `any tool count in the manifest equals tools list`() {
        val count = fetchAdvertisedToolNames().size
        val text = manifestText()
        val wrong = Regex("""(\d+)(?:[\s-]+MCP)?[\s-]+tools\b""", RegexOption.IGNORE_CASE).findAll(text)
            .filter { it.groupValues[1].toInt() != count }.map { it.value }.toList()
        assertTrue(wrong.isEmpty(), "server.json tool counts must be $count (tools/list): $wrong")
    }

    @Test
    fun `README has a Find Svod in MCP directories section that links server json`() {
        val text = section("Find Svod in MCP directories")
        assertTrue("`server.json`" in text, "README '## Find Svod in MCP directories' does not name `server.json`")
        assertTrue(
            "https://github.com/FleetQ/svod-engine/releases/latest" in text,
            "README '## Find Svod in MCP directories' does not link the Releases page",
        )
    }
}
