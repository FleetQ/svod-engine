package dev.svod.engine.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps docs/compare-hindsight.md honest: its sections, its MCP tool count (the live tools/list), a non-empty
 * "where Hindsight is ahead" section, and linked, dated sources.
 */
class CompareHindsightDocsConsistencyTest {

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
        const val PAGE = "docs/compare-hindsight.md"

        val SECTIONS = listOf("What both share", "Where Svod differs", "Where Hindsight is ahead", "Sources and date")

        val DATE = Regex("""\b\d{4}-\d{2}-\d{2}\b""")

        /** The tool names a WRITE agent sees over a stateless tools/list, fetched once per test class. */
        val ADVERTISED_TOOLS: List<String> by lazy { fetchAdvertisedToolNames() }
    }

    @Test
    fun `the page exists and has the expected sections`() {
        assertTrue(page.lines().first().trim() == "# Svod vs Hindsight", "$PAGE must start with '# Svod vs Hindsight'")
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
    fun `the Where Hindsight is ahead section is not empty`() {
        val bullets = section("Where Hindsight is ahead").lines().drop(1).count { it.startsWith("- ") }
        assertTrue(bullets >= 2, "$PAGE '## Where Hindsight is ahead' needs at least two '- ' bullets, found $bullets")
    }

    @Test
    fun `sources are linked and dated`() {
        val sources = section("Sources and date")
        assertTrue("https://" in sources, "$PAGE '## Sources and date' links no https:// source")
        assertTrue(DATE.containsMatchIn(sources), "$PAGE '## Sources and date' has no YYYY-MM-DD date")
        val sourceLines = sources.lines().toSet()
        val undated = page.lines().withIndex()
            .filter { (_, line) -> "https://" in line && line !in sourceLines && !DATE.containsMatchIn(line) }
            .map { (i, line) -> "line ${i + 1}: '${line.trim()}'" }
        assertTrue(undated.isEmpty(), "$PAGE links outside '## Sources and date' must carry a date: $undated")
    }
}
