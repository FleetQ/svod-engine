package dev.svod.engine.index

import dev.svod.engine.lifecycle.SvodConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultEvalTest {

    private fun seededVault(): IndexFixture {
        val fx = IndexFixture.create()
        runBlocking {
            fx.seed("ops/deploy.md", "# Deploy\n\nThe engine is deployed with launchd and a kickstart command.\n")
            fx.seed("ops/backup.md", "# Backup\n\nNightly restic snapshots go to the offsite bucket.\n")
            fx.seed("notes/garden.md", "# Garden\n\nTomatoes need watering every second evening.\n")
            fx.seed("notes/recipes.md", "# Recipes\n\nSourdough bread with a long cold proof.\n")
            fx.seed("notes/journal.md", "---\nprivate: true\n---\n# Journal\n\nA quiet evening in the garden.\n")
        }
        return fx
    }

    private fun noneConfig(fx: IndexFixture) = SvodConfig(
        vaultPath = fx.root.toString(),
        embedder = SvodConfig.EmbedderSettings(provider = "none"),
    )

    private fun golden(dir: Path, vararg lines: String): Path =
        Files.createTempFile(dir.parent, "golden-", ".jsonl").also { Files.write(it, lines.toList()) }

    @Test
    fun `scores keyword search on the vault, skips semantic without an embedder, and writes nothing`() {
        seededVault().use { fx ->
            val head = GitReader(fx.root).use { it.headCommit() }
            val file = golden(
                fx.root,
                "# Section 1 - en",
                """{"q": "restic snapshots offsite", "gains": {"ops/backup.md": 3, "notes/journal.md": 1}}""",
                """{"q": "tomatoes watering", "gains": {"notes/garden.md": 3, "missing/nowhere.md": 1}}""",
            )
            val out = mutableListOf<String>()
            val report = VaultEval.run(noneConfig(fx), file) { out += it }

            val keyword = assertNotNull(report.metrics[SearchMode.KEYWORD], out.joinToString("\n"))
            assertEquals(2, keyword.queries)
            // Both answering notes rank first; the private note and the unknown path each cost their
            // query half its recall.
            assertEquals(1.0, keyword.mrr, 1e-9)
            assertEquals(0.5, keyword.recallAt5, 1e-9)
            assertNotNull(report.metrics[SearchMode.HYBRID])
            assertTrue(SearchMode.SEMANTIC in report.metrics)
            assertNull(report.metrics[SearchMode.SEMANTIC])

            val text = out.joinToString("\n")
            assertTrue("missing/nowhere.md" in text, text)
            // A private note is in git at HEAD but never indexed: it must not be reported as a typo.
            assertTrue("notes/journal.md" !in text, text)
            assertTrue(out.any { "SEMANTIC" in it && "skipped" in it }, text)
            assertTrue(out.any { "[en]" in it }, text)
            assertEquals(head, GitReader(fx.root).use { it.headCommit() }, "eval must not commit")
        }
    }

    @Test
    fun `an index held by a running engine gives a clear error`() {
        seededVault().use { fx ->
            val file = golden(fx.root, """{"q": "restic", "gains": {"ops/backup.md": 3}}""")
            fx.newIndex(NoneEmbedder).use {
                val e = assertFailsWith<IllegalStateException> { VaultEval.run(noneConfig(fx), file) {} }
                assertTrue("stop the engine first" in (e.message ?: ""), e.message)
            }
        }
    }

    @Test
    fun `an invalid golden file is rejected with its line number`() {
        seededVault().use { fx ->
            val file = golden(fx.root, "# header", """{"q": "x"}""")
            val e = assertFailsWith<IllegalArgumentException> { VaultEval.run(noneConfig(fx), file) {} }
            assertTrue("line 2" in (e.message ?: ""), e.message)
        }
    }
}
