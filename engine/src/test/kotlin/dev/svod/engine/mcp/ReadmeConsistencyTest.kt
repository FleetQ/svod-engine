package dev.svod.engine.mcp

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

    /** The fenced json/toml/sh blocks under `### <heading>`, up to the next `###`/`####` heading. */
    private fun subsectionCodeBlocks(heading: String): List<Pair<String, String>> {
        val lines = readme.lines()
        val start = lines.indexOfFirst { it.trim() == "### $heading" }
        assertTrue(start >= 0, "README has no '### $heading' subsection")
        val end = (start + 1 until lines.size).firstOrNull {
            lines[it].startsWith("### ") || lines[it].startsWith("#### ") || lines[it].startsWith("## ")
        } ?: lines.size
        val blocks = mutableListOf<Pair<String, String>>()
        var i = start + 1
        while (i < end) {
            val open = lines[i]
            if (open.startsWith("```") && open.length > 3) {
                val lang = open.removePrefix("```").trim()
                val close = (i + 1 until end).first { lines[it] == "```" }
                if (lang in setOf("json", "toml", "sh")) blocks += lang to lines.subList(i + 1, close).joinToString("\n")
                i = close + 1
            } else {
                i++
            }
        }
        return blocks
    }

    @Test
    fun `the Connecting an LLM agent snippets equal the init output`() {
        val dir = Files.createTempDirectory("svod-readme")
        val config = dev.svod.engine.lifecycle.ConfigInit.generate(dir, "none", "friday-write-token")
        val expected = dev.svod.engine.lifecycle.ConfigInit.clientSnippets(config, "friday-write-token")
            .map { it.lang to it.body.trimEnd() }
        assertEquals(expected, subsectionCodeBlocks("Connecting an LLM agent"))
    }

    private companion object {
        /** A Markdown table cell boundary: a `|` not escaped as `\|`. */
        val UNESCAPED_PIPE = Regex("""(?<!\\)\|""")

        /** One cell of a table's header separator row, e.g. `---`, `:---`, `---:` or `:---:`. */
        val SEPARATOR_CELL = Regex(""":?-+:?""")

        /** The tool names a WRITE agent sees over a stateless tools/list, fetched once per test class. */
        val ADVERTISED_TOOLS: List<String> by lazy { fetchAdvertisedToolNames() }
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

    /**
     * The '## How it compares' table as rows of trimmed cells, in file order (header, separator, body).
     * Cells split on unescaped `|` only, so a `\|` inside a cell (as in `[[path\|Original]]`) stays one cell.
     */
    private fun comparisonRows(): List<List<String>> =
        section("How it compares").lines()
            .filter { it.trimStart().startsWith("|") }
            .map { line -> line.trim().removePrefix("|").removeSuffix("|").split(UNESCAPED_PIPE).map { it.trim() } }

    /** [comparisonRows] without the `|---|` separator row. */
    private fun comparisonTable(): List<List<String>> = comparisonRows().filterNot(::isSeparator)

    private fun isSeparator(row: List<String>): Boolean = row.all { SEPARATOR_CELL.matches(it) }

    @Test
    fun `the comparison table rows all have as many cells as the header`() {
        val rows = comparisonRows()
        assertTrue(rows.size > 2, "README '## How it compares' has no table")
        assertTrue(isSeparator(rows[1]), "comparison table has no separator line under the header: ${rows[1]}")
        val width = rows.first().size
        val bad = rows.drop(1).filter { it.size != width }.map { "${it.size} cells: '${it.joinToString(" | ")}'" }
        assertTrue(bad.isEmpty(), "comparison table rows (and separator) must have $width cells like the header: $bad")
    }

    @Test
    fun `the comparison table has a Basic Memory column and Licence and Hosted rows`() {
        val table = comparisonTable()
        assertTrue(table.isNotEmpty(), "README '## How it compares' has no table")
        assertTrue(table.first().any { "Basic Memory" in it }, "comparison table header has no Basic Memory column: ${table.first()}")
        assertTrue(table.any { "Licence" in it.firstOrNull().orEmpty() }, "comparison table has no Licence row")
        assertTrue(table.any { "Hosted" in it.firstOrNull().orEmpty() }, "comparison table has no Hosted tier row")
    }

    @Test
    fun `How it compares links to the Basic Memory page and the file exists`() {
        assertTrue(
            "](docs/compare-basic-memory.md)" in section("How it compares"),
            "README '## How it compares' has no link to docs/compare-basic-memory.md",
        )
        assertTrue(Files.exists(repoRoot.resolve("docs/compare-basic-memory.md")), "docs/compare-basic-memory.md does not exist")
    }

    @Test
    fun `How it compares links to the Mem0 page and the file exists`() {
        assertTrue(
            "](docs/compare-mem0.md)" in section("How it compares"),
            "README '## How it compares' has no link to docs/compare-mem0.md",
        )
        assertTrue(Files.exists(repoRoot.resolve("docs/compare-mem0.md")), "docs/compare-mem0.md does not exist")
    }

    @Test
    fun `How it compares links to the Letta page and the file exists`() {
        assertTrue(
            "](docs/compare-letta.md)" in section("How it compares"),
            "README '## How it compares' has no link to docs/compare-letta.md",
        )
        assertTrue(Files.exists(repoRoot.resolve("docs/compare-letta.md")), "docs/compare-letta.md does not exist")
    }

    @Test
    fun `the Letta column history cell reflects Context Repositories`() {
        val table = comparisonTable()
        assertTrue(table.isNotEmpty(), "README '## How it compares' has no table")
        val column = table.first().indexOfFirst { "Letta" in it }
        assertTrue(column >= 0, "comparison table header has no Letta column: ${table.first()}")
        val row = table.firstOrNull { "version history" in it.firstOrNull().orEmpty() }
        assertTrue(row != null, "comparison table has no 'version history' row")
        val cell = row.getOrNull(column).orEmpty()
        assertTrue(
            "Context Repositories" in cell && "❌" !in cell,
            "the Letta 'version history' cell must mention its git-backed Context Repositories, not ❌; found: '$cell'",
        )
    }

    @Test
    fun `the Connecting an LLM agent section links the agent-framework page`() {
        val lines = readme.lines()
        val start = lines.indexOfFirst { it.trim() == "### Connecting an LLM agent" }
        assertTrue(start >= 0, "README has no '### Connecting an LLM agent' section")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("#### ") || lines[it].startsWith("## ") } ?: lines.size
        val text = lines.subList(start, end).joinToString("\n")
        assertTrue(
            "](docs/integrations-agent-frameworks.md)" in text,
            "README '### Connecting an LLM agent' does not link docs/integrations-agent-frameworks.md",
        )
        assertTrue(Files.exists(repoRoot.resolve("docs/integrations-agent-frameworks.md")), "docs/integrations-agent-frameworks.md does not exist")
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
