package dev.svod.engine.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path

/** A throwaway vault directory plus a scope for the engine's write-actor. */
class VaultFixture(val root: Path) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: SvodEngine? = null

    fun open(): SvodEngine = SvodEngine.open(root, scope).also { engine = it }

    /** Simulate a crash: drop the engine instance (release lock/handles) without graceful work. */
    fun simulateCrash() {
        engine?.close()
        engine = null
    }

    override fun close() {
        engine?.close()
    }

    companion object {
        fun create(): VaultFixture = VaultFixture(Files.createTempDirectory("svod-test-"))
    }
}

/** Run `git <args>` in [root] using the system git CLI; returns (exitCode, stdout+stderr). */
object GitCli {
    fun run(root: Path, vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(listOf("git") + args)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = proc.waitFor()
        return code to out.trim()
    }

    fun fsckClean(root: Path): Boolean {
        val (code, out) = run(root, "fsck", "--full", "--strict")
        // fsck prints dangling-object notices to stdout; those are not corruption.
        val realErrors = out.lineSequence().filter {
            it.isNotBlank() && !it.startsWith("dangling") && !it.startsWith("Checking")
        }.toList()
        return code == 0 && realErrors.isEmpty()
    }

    fun isWorkingTreeClean(root: Path): Boolean {
        val (code, out) = run(root, "status", "--porcelain")
        return code == 0 && out.isBlank()
    }

    fun commitCount(root: Path): Int {
        val (code, out) = run(root, "rev-list", "--count", "HEAD")
        return if (code == 0) out.trim().toInt() else -1
    }
}
