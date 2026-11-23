package dev.svod.engine.mcp

import dev.svod.engine.lifecycle.SvodConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps docs/compare-mem0.md honest: its sections, its MCP tool count (the live tools/list) and the
 * import format and App API port it names (contract/openapi.yaml ImportRequest.format, SvodConfig.appApiPort).
 */
class CompareMem0DocsConsistencyTest {

    private val engineDir: Path = Paths.get(System.getProperty("svod.projectDir") ?: System.getProperty("user.dir"))
    private val repoRoot: Path = engineDir.toAbsolutePath().parent
    private val pagePath: Path = repoRoot.resolve(PAGE)

    private val page: String by lazy {
        assertTrue(Files.exists(pagePath), "$PAGE does not exist")
        Files.readString(pagePath)
    }

    private fun lineOf(text: String, offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1

    /** The page text from the `## <heading>` line up to the next `## ` heading. */
    private fun section(heading: String): String {
        val lines = page.lines()
        val start = lines.indexOfFirst { it.startsWith("## ") && it.removePrefix("## ").trim().startsWith(heading) }
        assertTrue(start >= 0, "$PAGE has no '## $heading' section")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private companion object {
        const val PAGE = "docs/compare-mem0.md"

        val SECTIONS = listOf("What both", "Where Svod differs", "Where Mem0 is ahead", "Importing", "Sources and date")

        /** The tool names a WRITE agent sees over a stateless tools/list, fetched once per test class. */
        val ADVERTISED_TOOLS: List<String> by lazy { fetchAdvertisedToolNames() }
    }

    @Test
    fun `the page exists and has the expected sections`() {
        SECTIONS.forEach { section(it) }
    }

    @Test
    fun `every tool count on the page equals the advertised tool list`() {
        val count = ADVERTISED_TOOLS.size
        val matches = Regex("""(\d+)(?:[\s-]+MCP)?[\s-]+tools\b""", RegexOption.IGNORE_CASE).findAll(page).toList()
        assertTrue(matches.isNotEmpty(), "$PAGE states no MCP tool count")
        val wrong = matches.filter { it.groupValues[1].toInt() != count }
            .map { "line ${lineOf(page, it.range.first)}: '${it.value}'" }
        assertTrue(wrong.isEmpty(), "$PAGE tool counts must be $count (tools/list): $wrong")
    }

    @Test
    fun `the import section names the mem0 format the contract accepts`() {
        val openapi = Files.readString(repoRoot.resolve("contract/openapi.yaml"))
        val lines = openapi.lines()
        val start = lines.indexOfFirst { it.trim() == "ImportRequest:" }
        assertTrue(start >= 0, "contract/openapi.yaml has no ImportRequest schema")
        // Only the ImportRequest schema body (deeper-indented lines), and only its `format:` property.
        val indent = lines[start].indexOfFirst { !it.isWhitespace() }
        val schema = lines.drop(start + 1).takeWhile { it.isBlank() || it.indexOfFirst { c -> !c.isWhitespace() } > indent }
        val format = schema.indexOfFirst { it.trim() == "format:" }
        assertTrue(format >= 0, "contract/openapi.yaml ImportRequest has no format property")
        val formatIndent = schema[format].indexOfFirst { !it.isWhitespace() }
        val enumLine = schema.drop(format + 1)
            .takeWhile { it.isBlank() || it.indexOfFirst { c -> !c.isWhitespace() } > formatIndent }
            .firstOrNull { it.trim().startsWith("enum:") }
        assertTrue(enumLine != null, "contract/openapi.yaml ImportRequest.format has no enum")
        val formats = Regex("""\[(.*)]""").find(enumLine)?.groupValues?.get(1).orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("mem0" in formats, "contract ImportRequest.format does not accept mem0: $formats")

        val text = section("Importing")
        assertTrue("/api/v1/import" in text, "$PAGE '## Importing' does not name POST /api/v1/import")
        val appApiPort = SvodConfig(vaultPath = "x").appApiPort
        val ports = Regex("""https?://(?:127\.0\.0\.1|localhost):(\S+?)/api/v1/import""").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue(ports.isNotEmpty(), "$PAGE '## Importing' shows no http://127.0.0.1:<port>/api/v1/import URL")
        assertTrue(
            ports.all { it == appApiPort.toString() },
            "$PAGE '## Importing' URLs must use the default App API port $appApiPort, found: $ports",
        )
        assertTrue(
            Regex(""""format"\s*:\s*"mem0"""").containsMatchIn(text),
            "$PAGE '## Importing' does not show \"format\": \"mem0\"",
        )
    }
}
