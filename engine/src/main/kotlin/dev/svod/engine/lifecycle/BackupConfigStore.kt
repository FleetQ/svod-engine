package dev.svod.engine.lifecycle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persistence for the backup-remote configuration set at runtime via PUT /api/v1/settings/backup,
 * stored at `<root>/.svod/backup.json` so it survives a restart (the startup [SvodConfig] file is
 * not rewritten while the engine runs). [remote] is always a credential-free URL or a
 * [dev.svod.engine.security.Secrets] reference — raw secrets are rejected at the API boundary
 * (`SvodConfig.remoteHasInlineCredentials`) and so never reach this file.
 */
@Serializable
data class BackupConfig(val remote: String, val enabled: Boolean = true)

class BackupConfigStore(root: Path) {
    private val file: Path = root.resolve(".svod").resolve("backup.json")
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val lock = Any()

    fun load(): BackupConfig? = synchronized(lock) {
        if (!Files.isRegularFile(file)) null
        else json.decodeFromString(BackupConfig.serializer(), Files.readString(file))
    }

    fun save(config: BackupConfig): Unit = synchronized(lock) {
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(BackupConfig.serializer(), config))
    }
}
