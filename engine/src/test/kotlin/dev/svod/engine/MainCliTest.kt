package dev.svod.engine

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.lifecycle.ApiCompatibility
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.lifecycle.SvodNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainCliTest {

    @Test
    fun `help and version flags are recognised without loading a config`() {
        for (a in listOf("--help", "-h", "help")) assertEquals(Command.Help, parseCommand(arrayOf(a)), a)
        for (a in listOf("--version", "-V", "version")) assertEquals(Command.Version, parseCommand(arrayOf(a)), a)
    }

    @Test
    fun `parseCommand keeps the existing run, import and clone forms`() {
        assertEquals(Command.Run(null), parseCommand(emptyArray()))
        assertEquals(Command.Run(Paths.get("/x/cfg.json")), parseCommand(arrayOf("/x/cfg.json")))
        assertEquals(Command.Import(listOf("a", "b")), parseCommand(arrayOf("import", "a", "b")))
        assertEquals(
            Command.Clone(listOf("r", "d", "v", "--branch", "main")),
            parseCommand(arrayOf("clone", "r", "d", "v", "--branch", "main")),
        )
        assertEquals(Command.Unknown("--bogus"), parseCommand(arrayOf("--bogus")))
    }

    @Test
    fun `usage lists every form and the flags`() {
        val u = usageText()
        assertTrue("svod-engine [config.json]" in u, u)
        assertTrue("svod-engine import <config.json> <obsidian-dir> [into]" in u, u)
        assertTrue("svod-engine clone <remote> <dest-dir> <vaultId> [local-branch] [--branch <sync-branch>]" in u, u)
        assertTrue("--help" in u && "--version" in u, u)
    }

    @Test
    fun `import-basic-memory is parsed and listed in the usage`() {
        assertEquals(Command.ImportBasicMemory(listOf("c", "d")), parseCommand(arrayOf("import-basic-memory", "c", "d")))
        val u = usageText()
        assertTrue("svod-engine import-basic-memory <config.json> <basic-memory-dir> [into]" in u, u)
    }

    @Test
    fun `import-claude-memory is parsed and listed in the usage`() {
        assertEquals(Command.ImportClaudeMemory(listOf("c", "d")), parseCommand(arrayOf("import-claude-memory", "c", "d")))
        val u = usageText()
        assertTrue("svod-engine import-claude-memory <config.json> <memory-dir> [into]" in u, u)
    }

    @Test
    fun `import-notion is parsed and listed in the usage`() {
        assertEquals(Command.ImportNotion(listOf("c", "d")), parseCommand(arrayOf("import-notion", "c", "d")))
        val u = usageText()
        assertTrue("svod-engine import-notion <config.json> <notion-export-dir> [into]" in u, u)
    }

    @Test
    fun `doctor is parsed with or without a config and listed in the usage`() {
        assertEquals(Command.Doctor(emptyList()), parseCommand(arrayOf("doctor")))
        assertEquals(Command.Doctor(listOf("/x/cfg.json")), parseCommand(arrayOf("doctor", "/x/cfg.json")))
        val u = usageText()
        assertTrue("svod-engine doctor [config.json]" in u, u)
    }

    @Test
    fun `eval is parsed and listed in the usage`() {
        assertEquals(Command.Eval(listOf("c.json", "g.jsonl")), parseCommand(arrayOf("eval", "c.json", "g.jsonl")))
        val u = usageText()
        assertTrue("svod-engine eval <config.json> <golden.jsonl>" in u, u)
    }

    @Test
    fun `digest is parsed and listed in the usage`() {
        assertEquals(Command.Digest(listOf("c.json", "proj")), parseCommand(arrayOf("digest", "c.json", "proj")))
        val u = usageText()
        assertTrue("svod-engine digest <config.json> [folder...]" in u, u)
    }

    @Test
    fun `export-rules is parsed and listed in the usage`() {
        assertEquals(
            Command.ExportRules(listOf("c.json", "AGENTS.md")),
            parseCommand(arrayOf("export-rules", "c.json", "AGENTS.md")),
        )
        val u = usageText()
        assertTrue("svod-engine export-rules <config.json> <AGENTS.md|CLAUDE.md>" in u, u)
    }

    private fun seedVault(): java.nio.file.Path {
        val dir = Files.createTempDirectory("svod-cli-vault-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        SvodEngine.open(dir, scope).use { engine ->
            runBlocking {
                val out = engine.write("rules/p.md", "---\ntype: policy\ntitle: CLI policy\n---\nbody\n", null, Author("a", "a@example.com"))
                assertTrue(out is WriteOutcome.Success, "$out")
            }
        }
        return dir
    }

    @Test
    fun `export-rules uses the vaults entry, not vaultPath`() {
        val real = seedVault()
        val stale = Files.createTempDirectory("svod-cli-stale-")
        val out = Files.createTempDirectory("svod-cli-out-")

        val vaultsOnly = SvodConfig(vaults = listOf(SvodConfig.VaultSettings(id = "main", path = real.toString())))
        val r = exportRules(vaultsOnly, out.resolve("AGENTS.md"))
        assertTrue(r.changed)
        assertEquals(1, r.rules)
        assertTrue("### CLI policy" in Files.readString(out.resolve("AGENTS.md")))

        val both = SvodConfig(
            vaultPath = stale.toString(),
            vaults = listOf(SvodConfig.VaultSettings(id = "main", path = real.toString())),
        )
        assertFailsWith<IllegalArgumentException> { exportRules(both, real.resolve("AGENTS.md")) }
        assertFalse(Files.exists(real.resolve("AGENTS.md")))
        assertEquals(1, exportRules(both, stale.resolve("AGENTS.md")).rules)
    }

    @Test
    fun `version names the engine and the contract`() {
        assertEquals(
            "svod-engine ${SvodNode.ENGINE_VERSION} (App API contract ${ApiCompatibility.CURRENT_CONTRACT_VERSION})",
            versionText(),
        )
    }

    @Test
    fun `startup banner warns when no agents are configured`() {
        val empty = SvodConfig(vaultPath = "/tmp/v", agents = emptyList())
        val lines = startupBanner(empty, 7517, 7518)
        assertTrue(lines.first().startsWith("svod-engine ${SvodNode.ENGINE_VERSION} ready on java"), lines.first())
        val warning = lines.singleOrNull { "no agents configured" in it }
        assertTrue(warning != null, lines.joinToString("\n"))
        assertTrue("agents[]" in warning && "POST /api/v1/agents" in warning, warning)

        val withAgent = empty.copy(agents = listOf(SvodConfig.AgentSettings(token = "t", agentId = "a", role = "READ_ONLY")))
        val ok = startupBanner(withAgent, 7517, 7518)
        assertFalse(ok.any { "no agents configured" in it }, ok.joinToString("\n"))
        assertEquals(4, ok.size)
    }
}
