package dev.svod.engine.lifecycle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * A stable, machine-global host identity for multi-machine sync. Each of a user's machines needs a
 * durable id so merge commits carry provenance ("edited on <machineA> vs <machineB>") and so a
 * machine's own canonical-ref pushes are recognizable. The id is generated ONCE and persisted to a
 * machine-global file ([DEFAULT_PATH]); every subsequent boot reads the same value, so it survives
 * restarts and is identical across all vaults on the machine.
 *
 * An explicit, non-default [SvodConfig.hostId] in config always wins (lets a user pin a readable
 * name); only the "local" placeholder default falls back to the persisted id.
 */
object HostIdentity {

    private const val PLACEHOLDER = "local"
    val DEFAULT_PATH: Path = Paths.get(System.getProperty("user.home"), ".config", "svod", "host-id")

    /**
     * The effective host id: [configured] when it is a real (non-placeholder) value, otherwise the
     * persisted machine id (generated + stored on first use).
     */
    fun resolve(configured: String?, path: Path = DEFAULT_PATH): String {
        val c = configured?.trim().orEmpty()
        if (c.isNotEmpty() && c != PLACEHOLDER) return c
        return loadOrCreate(path)
    }

    private fun loadOrCreate(path: Path): String {
        runCatching {
            if (Files.isRegularFile(path)) {
                val existing = Files.readString(path).trim()
                if (existing.isNotEmpty()) return existing
            }
        }
        val generated = generate()
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, generated)
        }
        return generated
    }

    /** A short, human-recognizable id: the local hostname (sanitized) plus a random suffix. */
    private fun generate(): String {
        val host = runCatching {
            java.net.InetAddress.getLocalHost().hostName
                .substringBefore('.')
                .lowercase()
                .replace(Regex("[^a-z0-9-]"), "-")
                .trim('-')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "host"
        val suffix = UUID.randomUUID().toString().substring(0, 6)
        return "$host-$suffix"
    }
}
