package dev.svod.engine.lifecycle

import java.nio.file.Files
import java.nio.file.Path

/**
 * The single mutable holder for the live [SvodConfig] and its on-disk file. The runtime controllers
 * that persist config edits — the embedder switch ([EmbedderController]) and vault creation
 * ([VaultController]) — mutate through here, so each edit builds on the latest state instead of
 * clobbering another's (every controller shares this one holder rather than an independent snapshot).
 *
 * [path] is null in tests/embeds with no config file; then changes are in-memory only.
 */
class ConfigStore(initial: SvodConfig, private val path: Path?) {

    @Volatile
    var config: SvodConfig = initial
        private set

    /** Apply [mutate] to the current config, persist it (when a path is known), and return the result. */
    @Synchronized
    fun update(mutate: (SvodConfig) -> SvodConfig): SvodConfig {
        val updated = mutate(config)
        config = updated
        path?.let { Files.writeString(it, SvodConfig.toJson(updated)) }
        return updated
    }
}
