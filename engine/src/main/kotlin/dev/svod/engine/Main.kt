package dev.svod.engine

import dev.svod.engine.core.SvodEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

/**
 * Minimal smoke entrypoint for the integrity core. The real servers (App API + MCP)
 * arrive in later build steps; for now this opens a vault, runs crash recovery, and
 * reports status — useful for manual verification and packaging wiring.
 */
fun main(args: Array<String>) = runBlocking {
    val vaultArg = args.firstOrNull() ?: "${System.getProperty("user.home")}/svod-vault"
    val root = Paths.get(vaultArg)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    SvodEngine.open(root, scope).use { engine ->
        println("svod-engine on java ${System.getProperty("java.version")}")
        println("vault: ${engine.root}")
        println("head:  ${engine.head() ?: "(empty)"}")
        println("files: ${engine.list().size}")
    }
}
