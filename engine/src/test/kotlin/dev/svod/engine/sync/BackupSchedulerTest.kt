package dev.svod.engine.sync

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.lifecycle.SvodConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val T = Author("tester", "t@svod.test")

class BackupSchedulerTest {

    private fun bareRemote(): Path = Files.createTempDirectory("svod-sched-bare-").also {
        Git.init().setBare(true).setDirectory(it.toFile()).call().close()
    }

    private fun binding(id: String, root: Path, settings: SvodConfig.BackupSettings) =
        BackupService.Binding(id, root, settings, store = null)

    @Test
    fun `backs up on startup when backupOnStartup is set`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bare = bareRemote()
        val dir = Files.createTempDirectory("svod-sched-startup-")
        val engine = SvodEngine.open(dir, scope)
        try {
            engine.write("a.md", "# A", null, T)
            val backup = BackupService(listOf(
                binding("v", dir, SvodConfig.BackupSettings(bare.toString(), enabled = true, backupOnStartup = true)),
            ))
            val sched = BackupScheduler(scope, backup, eventBus = null, tickMillis = 50)
            sched.start()
            withTimeout(5_000) { while (backup.lastBackupHead("v") == null) delay(25) }
            sched.stop()
            assertEquals(engine.head(), backup.lastBackupHead("v"))
        } finally {
            engine.close(); scope.cancel()
        }
    }

    @Test
    fun `backs up on the interval when the last backup is stale`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bare = bareRemote()
        val dir = Files.createTempDirectory("svod-sched-interval-")
        val engine = SvodEngine.open(dir, scope)
        try {
            engine.write("a.md", "# A", null, T)
            // No prior backup ⇒ dueForInterval is immediately true on the first tick; interval=1 (min).
            val backup = BackupService(listOf(
                binding("v", dir, SvodConfig.BackupSettings(bare.toString(), enabled = true, backupIntervalMinutes = 1)),
            ))
            val sched = BackupScheduler(scope, backup, eventBus = null, tickMillis = 50)
            sched.start()
            withTimeout(5_000) { while (backup.lastBackupHead("v") == null) delay(25) }
            sched.stop()
            assertEquals(engine.head(), backup.lastBackupHead("v"))
        } finally {
            engine.close(); scope.cancel()
        }
    }

    @Test
    fun `is a no-op when no schedule is configured`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bare = bareRemote()
        val dir = Files.createTempDirectory("svod-sched-off-")
        val engine = SvodEngine.open(dir, scope)
        try {
            engine.write("a.md", "# A", null, T)
            // Remote set + enabled, but no startup/interval/onChange ⇒ manual-only, scheduler does nothing.
            val backup = BackupService(listOf(
                binding("v", dir, SvodConfig.BackupSettings(bare.toString(), enabled = true)),
            ))
            val sched = BackupScheduler(scope, backup, eventBus = null, tickMillis = 50)
            sched.start()
            delay(300)
            sched.stop()
            assertNull(backup.lastBackupHead("v"))
        } finally {
            engine.close(); scope.cancel()
        }
    }
}
