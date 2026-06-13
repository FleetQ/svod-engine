package dev.svod.engine.sync

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.lifecycle.SvodConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val T = Author("tester", "t@svod.test")

class BackupServiceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun bareRemote(): Path = Files.createTempDirectory("svod-backup-bare-").also {
        Git.init().setBare(true).setDirectory(it.toFile()).call().close()
    }

    private fun openBare(path: Path): Repository =
        FileRepositoryBuilder().setGitDir(path.toFile()).setBare().build()

    @Test
    fun `backup pushes each vault's canonical branch to refs svod backup vaultId`() = runBlocking {
        val bare = bareRemote()
        val dir = Files.createTempDirectory("svod-backup-vault-")
        val engine = SvodEngine.open(dir, scope)
        try {
            engine.write("notes/a.md", "# A\nbody", expectedRevision = null, author = T)
            val head = engine.head()
            assertNotNull(head)

            val backup = BackupService(
                vaults = listOf(BackupService.VaultRef("primary", dir)),
                config = SvodConfig.BackupSettings(remote = bare.toString(), enabled = true),
            )
            val result = backup.backupNow()

            assertTrue(result.enabled)
            assertTrue(result.pushed, "backup should report a push")
            val vb = result.vaults.single()
            assertEquals("primary", vb.vaultId)
            assertTrue(vb.pushed)
            assertEquals(head, vb.head)

            // the backup ref exists on the remote and points at the vault's HEAD
            openBare(bare).use { repo ->
                val ref = repo.resolve("refs/svod/backup/primary")
                assertNotNull(ref, "refs/svod/backup/primary must exist on the remote")
                assertEquals(head, ref.name)
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `backup of multiple vaults writes a ref per vault`() = runBlocking {
        val bare = bareRemote()
        val dirA = Files.createTempDirectory("svod-backup-A-")
        val dirB = Files.createTempDirectory("svod-backup-B-")
        val engineA = SvodEngine.open(dirA, scope)
        val engineB = SvodEngine.open(dirB, scope)
        try {
            engineA.write("a.md", "# A", expectedRevision = null, author = T)
            engineB.write("b.md", "# B", expectedRevision = null, author = T)

            val backup = BackupService(
                vaults = listOf(BackupService.VaultRef("work", dirA), BackupService.VaultRef("home", dirB)),
                config = SvodConfig.BackupSettings(remote = bare.toString(), enabled = true),
            )
            backup.backupNow()

            openBare(bare).use { repo ->
                assertEquals(engineA.head(), repo.resolve("refs/svod/backup/work")?.name)
                assertEquals(engineB.head(), repo.resolve("refs/svod/backup/home")?.name)
            }
        } finally {
            engineA.close(); engineB.close()
        }
    }

    @Test
    fun `disabled backup is a graceful no-op`() = runBlocking {
        val dir = Files.createTempDirectory("svod-backup-off-")
        val engine = SvodEngine.open(dir, scope)
        try {
            engine.write("a.md", "# A", expectedRevision = null, author = T)
            val backup = BackupService(
                vaults = listOf(BackupService.VaultRef("primary", dir)),
                config = SvodConfig.BackupSettings(remote = "https://unused.example.com/x.git", enabled = false),
            )
            val result = backup.backupNow()
            assertFalse(result.enabled)
            assertFalse(result.pushed)
            assertTrue(result.vaults.isEmpty())
        } finally {
            engine.close()
        }
    }

    @Test
    fun `unconfigured backup is a no-op`() = runBlocking {
        val backup = BackupService(vaults = emptyList(), config = null)
        val result = backup.backupNow()
        assertFalse(result.enabled)
        assertFalse(result.pushed)
        assertNull(result.remote)
    }

    @Test
    fun `configure swaps the destination for the next backup`() = runBlocking {
        val bare = bareRemote()
        val dir = Files.createTempDirectory("svod-backup-cfg-")
        val engine = SvodEngine.open(dir, scope)
        try {
            engine.write("a.md", "# A", expectedRevision = null, author = T)
            val backup = BackupService(vaults = listOf(BackupService.VaultRef("primary", dir)), config = null)
            assertFalse(backup.backupNow().enabled) // unconfigured first

            backup.configure(SvodConfig.BackupSettings(remote = bare.toString(), enabled = true))
            val result = backup.backupNow()
            assertTrue(result.pushed)
            openBare(bare).use { repo -> assertNotNull(repo.resolve("refs/svod/backup/primary")) }
        } finally {
            engine.close()
        }
    }
}
