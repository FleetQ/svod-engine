package dev.svod.engine

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.lifecycle.SvodNode
import dev.svod.engine.migrate.ObsidianImport
import dev.svod.engine.security.SecretScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch

/**
 * Engine entrypoint. Reads a config file (first arg, else a default vault), starts the node,
 * and blocks until a signal. launchd manages the process lifecycle (RunAtLoad + KeepAlive +
 * kickstart); on SIGTERM the JVM runs the shutdown hook for a graceful, lossless stop.
 *
 * One-shot import mode: `svod-engine import <config.json> <obsidian-dir> [into]` migrates an
 * Obsidian vault into the configured vault and exits (run while the daemon is stopped — the
 * vault lock is exclusive; while it's running, use POST /api/v1/import instead).
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "import") return runImport(args.drop(1))

    val config = args.firstOrNull()
        ?.let { SvodConfig.loadOrThrowValidated(Paths.get(it)) }
        ?: SvodConfig.default(Paths.get(System.getProperty("user.home"), "svod-vault"))

    val node = SvodNode.start(config)
    Runtime.getRuntime().addShutdownHook(Thread({ node.shutdown() }, "svod-shutdown"))

    println("svod-engine ready on java ${System.getProperty("java.version")}")
    println("  vault:   ${config.vaultPath}")
    println("  app api: http://${config.host}:${node.appApiPort}  (loopback)")
    println("  mcp:     http://${config.host}:${node.mcpPort}")

    CountDownLatch(1).await() // park until SIGTERM; the shutdown hook does the cleanup
}

private fun runImport(args: List<String>) {
    require(args.size >= 2) { "usage: svod-engine import <config.json> <obsidian-dir> [into]" }
    val config = SvodConfig.loadOrThrowValidated(Paths.get(args[0]))
    val source = Paths.get(args[1])
    val into = args.getOrNull(2) ?: ""
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    SvodEngine.open(config.vault(), scope, SecretScanner(config.secretScanning)).use { engine ->
        val r = runBlocking { ObsidianImport.import(source, engine, into = into) }
        println("import: ${r.imported.size} imported, ${r.unchanged.size} unchanged, ${r.skipped.size} skipped")
        if (r.skipped.isNotEmpty()) println("  skipped (present-but-differs / blocked): ${r.skipped.joinToString(", ")}")
    }
}
