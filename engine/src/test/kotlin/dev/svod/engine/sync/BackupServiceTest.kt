package dev.svod.engine.sync

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.lifecycle.BackupConfigStore
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
import kotlin.test.assertTrue

private val T = Author("tester", "t@svod.test")

class BackupServiceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun bareRemote(): Path = Files.createTempDirectory("svod-backup-bare-").also {
        Git.init().setBare(true).setDirectory(it.toFile()).call().close()
    }

    private fun openBare(path: Path): Repository =
        FileRepositoryBuilder().setGitDir(path.toFile()).setBare().build()

    private fun binding(id: String, root: Path, remote: String?, enabled: Boolean = true, store: BackupConfigStore? = null) =
        BackupService.Binding(id, root, remote?.let { SvodConfig.BackupSettings(it, enabled) }, store)

    @Test
    fun `each vault backs up to its OWN remote`() = runBlocking {
        val bareWork = bareRemote()
        val bareHome = bareRemote()
        val dirW = Files.createTempDirectory("svod-work-")
        val dirH = Files.createTempDirectory("svod-home-")
        val work = SvodEngine.open(dirW, scope)
        val home = SvodEngine.open(dirH, scope)
        try {
            work.write("a.md", "# work", null, T)
            home.write("b.md", "# home", null, T)

            val backup = BackupService(listOf(
                binding("work", dirW, bareWork.toString()),
                binding("home", dirH, bareHome.toString()),
            ))
            assertEquals("ok", backup.backupNow("work").status)
            assertEquals("ok", backup.backupNow("home").status)

            // each vault's branch landed on its OWN remote, and not on the other's
            openBare(bareWork).use { repo ->
                assertEquals(work.head(), repo.resolve("refs/svod/backup/work")?.name)
                assertEquals(null, repo.resolve("refs/svod/backup/home"), "home must not be on work's remote")
            }
            openBare(bareHome).use { repo ->
                assertEquals(home.head(), repo.resolve("refs/svod/backup/home")?.name)
            }
        } finally {
            work.close(); home.close()
        }
    }

    @Test
    fun `disabled or unconfigured vault is a graceful no-op`() = runBlocking {
        val dir = Files.createTempDirectory("svod-off-")
        val engine = SvodEngine.open(dir, scope)
        try {
            engine.write("a.md", "# A", null, T)
            val backup = BackupService(listOf(
                binding("off", dir, "https://unused.example.com/x.git", enabled = false),
                binding("none", dir, null),
            ))
            assertEquals("disabled", backup.backupNow("off").status)
            assertEquals("disabled", backup.backupNow("none").status)
            assertEquals("unknown_vault", backup.backupNow("ghost").status)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `configure sets a vault's destination and persists it to that vault's store`() = runBlocking {
        val bare = bareRemote()
        val dir = Files.createTempDirectory("svod-cfg-")
        val engine = SvodEngine.open(dir, scope)
        try {
            engine.write("a.md", "# A", null, T)
            val store = BackupConfigStore(dir)
            val backup = BackupService(listOf(binding("primary", dir, null, store = store)))
            assertEquals("disabled", backup.backupNow("primary").status) // unconfigured first

            backup.configure("primary", SvodConfig.BackupSettings(bare.toString(), enabled = true))
            assertEquals("ok", backup.backupNow("primary").status)
            openBare(bare).use { repo -> assertNotNull(repo.resolve("refs/svod/backup/primary")) }

            // persisted: a fresh service that loads from the store has the destination already
            val reloaded = BackupService(listOf(
                BackupService.Binding("primary", dir, store.load()?.let { SvodConfig.BackupSettings(it.remote, it.enabled) }, store),
            ))
            assertEquals(bare.toString(), reloaded.configOf("primary")?.remote)
            assertTrue(reloaded.configOf("primary")!!.enabled)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an unreachable remote yields status error, not a thrown exception`() = runBlocking {
        val dir = Files.createTempDirectory("svod-unreach-")
        val engine = SvodEngine.open(dir, scope)
        try {
            engine.write("a.md", "# A", null, T)
            // a remote that cannot be reached: a transport failure must be caught, not propagated
            // (the App API maps it to a 409, never a 500).
            val backup = BackupService(listOf(binding("v", dir, "/no/such/path/backup.git")))
            val r = backup.backupNow("v")
            assertEquals("error", r.status)
            assertFalse(r.pushed)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `records last-backup markers, persists them, and skips when nothing changed`() = runBlocking {
        val bare = bareRemote()
        val dir = Files.createTempDirectory("svod-last-")
        val engine = SvodEngine.open(dir, scope)
        try {
            engine.write("a.md", "# A", null, T)
            val store = BackupConfigStore(dir)
            val backup = BackupService(listOf(binding("v", dir, bare.toString(), store = store)))

            val first = backup.backupNow("v")
            assertEquals("ok", first.status)
            assertEquals(engine.head(), backup.lastBackupHead("v"))
            assertNotNull(backup.lastBackupAt("v"))
            // The markers are persisted (a fresh service reloading the store sees them).
            assertEquals(engine.head(), store.load()?.lastBackupHead)

            // Nothing changed since the last success ⇒ the next call is a no-op (no redundant push).
            assertEquals("noop", backup.backupNow("v").status)

            // A new write makes the head differ again ⇒ backup pushes once more.
            engine.write("b.md", "# B", null, T)
            assertEquals("ok", backup.backupNow("v").status)
            assertEquals(engine.head(), backup.lastBackupHead("v"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `backupAll backs up every configured vault`() = runBlocking {
        val bare = bareRemote()
        val dirA = Files.createTempDirectory("svod-A-")
        val dirB = Files.createTempDirectory("svod-B-")
        val a = SvodEngine.open(dirA, scope)
        val b = SvodEngine.open(dirB, scope)
        try {
            a.write("a.md", "# A", null, T)
            b.write("b.md", "# B", null, T)
            val backup = BackupService(listOf(
                binding("a", dirA, bare.toString()),
                binding("b", dirB, bare.toString()),
            ))
            val results = backup.backupAll()
            assertTrue(results.all { it.status == "ok" })
            openBare(bare).use { repo ->
                assertEquals(a.head(), repo.resolve("refs/svod/backup/a")?.name)
                assertEquals(b.head(), repo.resolve("refs/svod/backup/b")?.name)
            }
        } finally {
            a.close(); b.close()
        }
    }
}
