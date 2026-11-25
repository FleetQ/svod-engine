package dev.svod.engine.migrate

import dev.svod.engine.Command
import dev.svod.engine.api.ImportRequestDto
import dev.svod.engine.api.RegisterSourceRequestDto
import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.VaultPath
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.mcp.fetchAdvertisedToolNames
import dev.svod.engine.parseCommand
import dev.svod.engine.usageText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.SerialDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Keeps docs/obsidian.md honest: the CLI commands, MCP tools, config keys, API fields and the
 * import / external-edit behaviour it describes are checked against the engine.
 */
class ObsidianGuideDocsTest {

    private val engineDir: Path = Paths.get(System.getProperty("svod.projectDir") ?: System.getProperty("user.dir"))
    private val repoRoot: Path = engineDir.toAbsolutePath().parent
    private val pagePath: Path = repoRoot.resolve(PAGE)

    private val page: String by lazy {
        assertTrue(Files.exists(pagePath), "$PAGE does not exist")
        Files.readString(pagePath)
    }

    /** The page text from the `## <heading>` line up to the next `## ` heading. */
    private fun section(heading: String): String {
        val lines = page.lines()
        val start = lines.indexOfFirst { it.startsWith("## ") && it.removePrefix("## ").trim().startsWith(heading) }
        assertTrue(start >= 0, "$PAGE has no '## $heading' section")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun assertBackticked(text: String, value: String, where: String) =
        assertTrue("`$value`" in text, "$PAGE '$where' does not mention `$value`")

    private fun backticked(text: String): List<String> = Regex("`([^`\n]+)`").findAll(text).map { it.groupValues[1] }.toList()

    private fun <T> withEngine(block: suspend (SvodEngine, Path) -> T): T = runBlocking {
        val root = Files.createTempDirectory("svod-obsidian-guide-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SvodEngine.open(root, scope).use { block(it, root) }
        } finally {
            scope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    /** True when [path] (dot-separated) resolves element by element through [descriptor]. */
    private fun resolves(descriptor: SerialDescriptor, path: String): Boolean {
        var d = descriptor
        for (part in path.split('.')) {
            val i = (0 until d.elementsCount).firstOrNull { d.getElementName(it) == part } ?: return false
            d = d.getElementDescriptor(i)
        }
        return true
    }

    private companion object {
        const val PAGE = "docs/obsidian.md"
        val SECTIONS = listOf(
            "Open your vault in Obsidian",
            "Links and backlinks",
            "Import an existing Obsidian vault",
            "Notes that live elsewhere",
            "What Obsidian does not do here",
        )
        val ADVERTISED_TOOLS: List<String> by lazy { fetchAdvertisedToolNames() }
    }

    @Test
    fun pageExistsAndIsLinked() {
        assertTrue(Files.exists(pagePath), "$PAGE does not exist")
        SECTIONS.forEach { section(it) }
        val readme = Files.readString(repoRoot.resolve("README.md"))
        assertTrue("]($PAGE)" in readme, "README.md does not link $PAGE")
        val basicMemory = Files.readString(repoRoot.resolve("docs/compare-basic-memory.md"))
        assertTrue("](obsidian.md)" in basicMemory, "docs/compare-basic-memory.md does not link obsidian.md")
        // Every relative link on the page points at a file that exists.
        val broken = Regex("""\]\(([^)#\s]+)(?:#[^)]*)?\)""").findAll(page).map { it.groupValues[1] }
            .filterNot { it.startsWith("http://") || it.startsWith("https://") }
            .filterNot { Files.exists(pagePath.parent.resolve(it).normalize()) }
            .toList()
        assertTrue(broken.isEmpty(), "$PAGE links files that do not exist: $broken")
    }

    @Test
    fun everyCliCommandExists() {
        val words = Regex("""svod-engine ([a-z][a-z-]*)""").findAll(page).map { it.groupValues[1] }.toSet()
        assertTrue("import" in words, "$PAGE does not show `svod-engine import`")
        words.forEach { w ->
            val cmd = parseCommand(arrayOf(w, "config.json", "dir"))
            assertTrue(cmd !is Command.Unknown && cmd !is Command.Run, "$PAGE names `svod-engine $w`, which is not a subcommand ($cmd)")
        }
        val usage = usageText().lines().map { it.trim() }.single { it.startsWith("svod-engine import ") }
        assertTrue(usage in page, "$PAGE must show the import usage line exactly as usageText() prints it: $usage")
        assertTrue("--follow" !in usage, "the import CLI now takes a symlink flag; update $PAGE '## Import an existing Obsidian vault'")
    }

    @Test
    fun everyMcpToolNamedExists() {
        val links = section("Links and backlinks")
        val asTools = Regex("""`([a-z_]+)`\s+(?:MCP\s+)?tool\b""").findAll(links).map { it.groupValues[1] }.toSet()
        listOf("move", "link", "graph_query").forEach { t ->
            assertTrue(t in asTools, "$PAGE '## Links and backlinks' must present `$t` as a tool; found $asTools")
        }
        val unknown = asTools - ADVERTISED_TOOLS.toSet()
        assertTrue(unknown.isEmpty(), "$PAGE presents these as MCP tools but tools/list does not have them: $unknown")
        // backlinks / outlinks are result fields, not tools; the page names them and the code returns them.
        assertBackticked(links, "backlinks", "## Links and backlinks")
        assertBackticked(links, "outlinks", "## Links and backlinks")
        assertBackticked(links, "rewrittenBacklinks", "## Links and backlinks")
        val tools = Files.readString(engineDir.resolve("src/main/kotlin/dev/svod/engine/mcp/SvodTools.kt"))
        listOf("backlinks", "outlinks", "rewrittenBacklinks").forEach {
            assertTrue("putJsonArray(\"$it\")" in tools, "SvodTools.kt no longer returns `$it`; update $PAGE")
        }
        // Cross-vault relinking: one engine.write (one commit) per changed note, best-effort, App API only.
        val server = Files.readString(engineDir.resolve("src/main/kotlin/dev/svod/engine/api/AppApiServer.kt"))
        val relinkStart = server.indexOf("private suspend fun crossVaultRelink(")
        assertTrue(relinkStart >= 0, "AppApiServer.kt has no crossVaultRelink; update $PAGE '## Links and backlinks'")
        val relinkEnd = server.indexOf("\n    }\n", relinkStart)
        assertTrue(relinkEnd >= 0, "cannot find the end of crossVaultRelink in AppApiServer.kt; update this test")
        val relink = server.substring(relinkStart, relinkEnd)
        assertTrue(
            "runCatching { v.engine.write(path, updated, fc.revision, author) }" in relink && "writeBatch" !in relink && "writeGuarded" !in relink,
            "crossVaultRelink no longer commits each note separately and best-effort; update $PAGE '## Links and backlinks'",
        )
        assertTrue("crossVaultRelink" !in tools, "MCP move now relinks other vaults; update $PAGE '## Links and backlinks'")
        val flat = links.replace(Regex("""\s+"""), " ")
        assertTrue("best-effort update that commits each changed note on its own" in flat, "$PAGE must say cross-vault relinking commits each changed note on its own")
        assertTrue("does not rewrite links in other vaults" in flat, "$PAGE must say MCP `move` does not relink other vaults")
        assertTrue("commit per vault" !in flat, "$PAGE must not claim one commit per vault for cross-vault relinking")
        val counts = Regex("""(\d+)(?:[\s-]+MCP)?[\s-]+tools\b""", RegexOption.IGNORE_CASE).findAll(page).map { it.groupValues[1].toInt() }.toList()
        assertTrue(counts.all { it == ADVERTISED_TOOLS.size }, "$PAGE tool counts must be ${ADVERTISED_TOOLS.size}: $counts")

        // The claim behind it: a move rewrites the backlinks in the same commit.
        withEngine { e, _ ->
            val a = Author("a", "a@x")
            assertTrue(e.write("a.md", "# A\n", null, a) is WriteOutcome.Success)
            assertTrue(e.write("b.md", "see [[a]]\n", null, a) is WriteOutcome.Success)
            val moved = e.moveWithLinks("a.md", "c.md", assertNotNull(e.read("a.md")).revision, a)
            assertTrue(moved.outcome is WriteOutcome.Success, "move failed: ${moved.outcome}")
            assertEquals(listOf("b.md"), moved.rewrittenBacklinks)
            assertTrue("[[a]]" !in assertNotNull(e.read("b.md")).text, "the backlink in b.md was not rewritten")
            assertEquals(e.history("c.md").first().commit, e.history("b.md").first().commit, "move and backlink rewrite are not one commit")
        }
    }

    @Test
    fun everyConfigKeyExists() {
        val sources = section("Notes that live elsewhere")
        val config = SvodConfig.serializer().descriptor
        val topLevel = (0 until config.elementsCount).map { config.getElementName(it) }.toSet()
        listOf("sourceSync", "sourceSync.onStartup", "sourceSync.intervalMinutes").forEach { assertBackticked(sources, it, "## Notes that live elsewhere") }
        val keys = backticked(sources).filter { Regex("""[a-z][A-Za-z]*(\.[a-z][A-Za-z]*)*""").matches(it) && it.substringBefore('.') in topLevel }
        keys.forEach { k -> assertTrue(resolves(config, k), "$PAGE names config key `$k`, which SvodConfig does not have") }

        // Request fields the page names for POST /api/v1/sources and POST /api/v1/import.
        val register = RegisterSourceRequestDto.serializer().descriptor
        listOf("path", "into", "followSymlinks", "prune", "autoSync", "writeBack").forEach { f ->
            assertTrue(resolves(register, f), "RegisterSourceRequestDto has no `$f`")
            assertBackticked(sources, f, "## Notes that live elsewhere")
        }
        // When sources sync on their own: autoSync sources are watched, so "only when you call the API"
        // must be limited to sources without autoSync.
        val watchManager = Files.readString(engineDir.resolve("src/main/kotlin/dev/svod/engine/sources/SourceWatchManager.kt"))
        assertTrue(
            "store.list().filter { it.autoSync && pathExists(it) }" in watchManager,
            "SourceWatchManager no longer watches exactly the autoSync sources; update $PAGE '## Notes that live elsewhere'",
        )
        assertTrue(
            Regex("""`autoSync`:[^\n]*watcher[^\n]*re-syncs""").containsMatchIn(sources),
            "$PAGE must say an `autoSync` source is re-synced by a watcher when its files change",
        )
        val onlyApi = Regex("""(?s)[^.]*only when you call the API[^.]*""").findAll(sources).map { it.value }.toList()
        assertTrue(onlyApi.isNotEmpty(), "$PAGE must say when a source syncs without a schedule")
        onlyApi.forEach {
            assertTrue("without `autoSync`" in it, "$PAGE: '${it.trim()}' must be limited to sources without `autoSync`")
        }
        val server = Files.readString(engineDir.resolve("src/main/kotlin/dev/svod/engine/api/AppApiServer.kt"))
        assertTrue("post(\"/api/v1/sources\")" in server && "post(\"/api/v1/sources/sync\")" in server, "the sources routes moved; update $PAGE")
        assertTrue("/api/v1/sources" in sources, "$PAGE '## Notes that live elsewhere' must name /api/v1/sources")
        assertTrue("(adr/0016-external-sources-and-symlink-import.md)" in sources, "$PAGE '## Notes that live elsewhere' must link ADR-0016")

        val import = section("Import an existing Obsidian vault")
        val importReq = ImportRequestDto.serializer().descriptor
        listOf("source", "format", "into", "followSymlinks").forEach { f ->
            assertTrue(resolves(importReq, f), "ImportRequestDto has no `$f`")
            assertTrue("\"$f\"" in import, "$PAGE '## Import an existing Obsidian vault' request does not show \"$f\"")
        }
        assertTrue(Regex(""""format"\s*:\s*"obsidian"""").containsMatchIn(import), "$PAGE must show \"format\": \"obsidian\"")
        val port = SvodConfig(vaultPath = "x").appApiPort
        val ports = Regex("""https?://(?:127\.0\.0\.1|localhost):(\d+)/api/v1/import""").findAll(import).map { it.groupValues[1] }.toList()
        assertTrue(ports.isNotEmpty() && ports.all { it == port.toString() }, "$PAGE import URLs must use port $port: $ports")
    }

    @Test
    fun importClaimsHold() {
        val import = section("Import an existing Obsidian vault")
        assertBackticked(import, ".obsidian/", "## Import an existing Obsidian vault")
        assertBackticked(import, "followSymlinks", "## Import an existing Obsidian vault")
        assertTrue("idempotent" in import.lowercase(), "$PAGE must say import is idempotent")
        assertTrue("attachments" in import.lowercase(), "$PAGE must say attachments are kept")
        assertTrue("never overwrites" in import.lowercase(), "$PAGE must say import never overwrites a changed note")
        assertTrue("engine stopped" in import.lowercase(), "$PAGE must say the CLI runs with the engine stopped")

        val source = Files.createTempDirectory("obsidian-guide-src-")
        try {
            Files.createDirectories(source.resolve(".obsidian"))
            Files.writeString(source.resolve(".obsidian/app.json"), "{}")
            val note = "---\ntags: [x]\n---\n# Note\nsee [[other]] ![[pic.png]]\n"
            Files.writeString(source.resolve("note.md"), note)
            val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0xFF.toByte())
            Files.write(source.resolve("pic.png"), png)

            withEngine { e, _ ->
                val first = ObsidianImport.import(source, e)
                assertEquals(listOf("note.md", "pic.png"), first.imported)
                assertTrue(e.list().none { ".obsidian" in it }, ".obsidian/ was imported")
                assertEquals(note, assertNotNull(e.read("note.md")).text)
                assertContentEquals(png, e.readBytes("pic.png"))

                val second = ObsidianImport.import(source, e)
                assertEquals(emptyList(), second.imported)
                assertEquals(listOf("note.md", "pic.png"), second.unchanged)

                // A note changed in the vault is left alone on re-import.
                val rev = assertNotNull(e.read("note.md")).revision
                assertTrue(e.write("note.md", "# Edited\n", rev, Author("u", "u@x")) is WriteOutcome.Success)
                val third = ObsidianImport.import(source, e)
                assertEquals(listOf("note.md"), third.skipped)
                assertEquals("# Edited\n", assertNotNull(e.read("note.md")).text)
            }
        } finally {
            source.toFile().deleteRecursively()
        }
    }

    @Test
    fun externalEditAttribution() {
        val open = section("Open your vault in Obsidian")
        assertBackticked(open, Author.EXTERNAL.name, "## Open your vault in Obsidian")
        assertEquals("external", Author.EXTERNAL.name, "Author.EXTERNAL was renamed; update $PAGE")
        // Engine-owned folders and the soft-delete folder.
        listOf(".git", ".svod", ".trash").forEach { assertTrue(it in VaultPath.RESERVED_TOP, "VaultPath.RESERVED_TOP no longer has $it") }
        listOf(".git/", ".svod/", ".trash/", ".obsidian/", ".gitignore").forEach { assertBackticked(open, it, "## Open your vault in Obsidian") }
        val watcher = Files.readString(engineDir.resolve("src/main/kotlin/dev/svod/engine/watch/FileWatcher.kt"))
        assertTrue("""top == ".git" || top == ".svod"""" in watcher, "FileWatcher's ignore rule changed; update $PAGE '## Open your vault in Obsidian'")
        assertTrue("does not read `.gitignore`" in open, "$PAGE must say the watcher does not read `.gitignore`")

        withEngine { e, root ->
            // An edit made on disk (as Obsidian does) is committed as `external`.
            assertTrue(e.write("notes/a.md", "# A\n", null, Author("agent", "agent@x")) is WriteOutcome.Success)
            Files.writeString(root.resolve("notes/a.md"), "# A\nedited in Obsidian\n")
            assertNotNull(e.ingestExternalChanges(), "an on-disk edit produced no commit")
            val last = e.history("notes/a.md").first()
            assertEquals(Author.EXTERNAL.name, last.authorName)

            // The watcher's path-scoped ingest commits Obsidian's workspace file even when .gitignore lists it.
            Files.writeString(root.resolve(".gitignore"), ".obsidian/workspace*.json\n")
            Files.createDirectories(root.resolve(".obsidian"))
            Files.writeString(root.resolve(".obsidian/workspace.json"), "{\"main\":{}}")
            assertNotNull(e.ingestExternalChanges(listOf(".gitignore", ".obsidian/workspace.json")), "the workspace change produced no commit")
            val ws = e.history(".obsidian/workspace.json")
            assertTrue(ws.isNotEmpty(), "commitPaths now honours .gitignore; $PAGE may recommend ignoring .obsidian/workspace*.json")
            assertEquals(Author.EXTERNAL.name, ws.first().authorName)
        }
    }
}
