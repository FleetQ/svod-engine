package dev.svod.engine

import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.lifecycle.SvodNode
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch

/**
 * Engine entrypoint. Reads a config file (first arg, else a default vault), starts the node,
 * and blocks until a signal. launchd manages the process lifecycle (RunAtLoad + KeepAlive +
 * kickstart); on SIGTERM the JVM runs the shutdown hook for a graceful, lossless stop.
 */
fun main(args: Array<String>) {
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
