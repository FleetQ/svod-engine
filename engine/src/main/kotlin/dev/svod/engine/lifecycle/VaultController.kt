package dev.svod.engine.lifecycle

import dev.svod.engine.api.CreateVaultRequest
import dev.svod.engine.api.DeleteVaultResultDto
import dev.svod.engine.api.VaultCreator
import dev.svod.engine.api.VaultRemover
import dev.svod.engine.api.VaultView
import dev.svod.engine.events.EventBus
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Runtime vault creation + removal for the App API. Creation makes the target directory (refusing a
 * non-empty one), opens a [VaultContext] — which `git init`s the repo with a seed commit, acquires
 * its exclusive lock and builds the keyword index — hot-adds it to the running [VaultManager] (so
 * GET /vaults and `?vault=` see it at once), then registers it in the persistent config (via the
 * shared [ConfigStore]) so it survives a restart. New vaults start solo: no sync, no backup.
 * Removal is the inverse: release the vault's lock + handles, unregister it, drop it from the config.
 */
class VaultController(
    private val vaults: VaultManager,
    private val configStore: ConfigStore,
    private val scope: CoroutineScope,
    private val eventBus: EventBus,
    private val hostId: String,
) : VaultCreator, VaultRemover {

    override suspend fun create(req: CreateVaultRequest): VaultView {
        val id = req.id
        if (!ID_PATTERN.matches(id)) {
            throw VaultCreator.InvalidRequest("invalid vault id '$id' (must match ${ID_PATTERN.pattern})")
        }

        val config = configStore.config
        if (vaults.context(id) != null || config.resolvedVaults().any { it.id == id }) {
            throw VaultCreator.Conflict("vault id already exists: $id")
        }

        val dir = resolvePath(req, config)
        if (!dir.isAbsolute) throw VaultCreator.InvalidRequest("vault path must be absolute: $dir")
        if (Files.exists(dir)) {
            if (!Files.isDirectory(dir)) throw VaultCreator.InvalidRequest("target path exists and is not a directory: $dir")
            val nonEmpty = Files.newDirectoryStream(dir).use { it.iterator().hasNext() }
            if (nonEmpty) throw VaultCreator.Conflict("target directory exists and is not empty: $dir")
        }
        try {
            Files.createDirectories(dir)
        } catch (e: Exception) {
            throw VaultCreator.NotWritable("could not create vault directory $dir: ${e.message}")
        }
        if (!Files.isWritable(dir)) throw VaultCreator.NotWritable("vault directory not writable: $dir")

        val vs = SvodConfig.VaultSettings(id = id, path = dir.toString(), name = req.name ?: id)
        // open() does git init + seed commit + lock + keyword index for the (empty) vault.
        val vc = VaultContext.open(vs, config, scope, eventBus, hostId)
        try {
            vaults.register(vc)
        } catch (t: Throwable) {
            vc.close()
            throw t
        }
        // Persist so the vault survives a restart. resolvedVaults() materializes a legacy single
        // vaultPath into the explicit list before appending, so the original vault isn't dropped.
        configStore.update { cur ->
            cur.copy(
                vaults = cur.resolvedVaults() + vs,
                vaultPath = null,
                defaultVault = cur.defaultVaultId(),
            )
        }
        return vc
    }

    override suspend fun delete(id: String, deleteFiles: Boolean): DeleteVaultResultDto {
        val config = configStore.config
        val resolved = config.resolvedVaults()
        val settings = resolved.firstOrNull { it.id == id }
        if (settings == null && vaults.context(id) == null) {
            throw VaultRemover.UnknownVault("unknown vault: $id")
        }
        // The engine must always keep a valid default and at least one vault: refuse both before any
        // teardown. (A single-vault config is necessarily also the default, so the last-vault guard
        // is checked first to surface the more specific reason.)
        if (resolved.size <= 1) {
            throw VaultRemover.Conflict("cannot delete the last remaining vault: $id")
        }
        if (id == config.defaultVaultId()) {
            throw VaultRemover.Conflict("cannot delete the default vault: $id (reassign the default first)")
        }

        // The on-disk directory to return (and optionally delete): the persisted path, falling back
        // to the live engine root.
        val dir = settings?.path ?: vaults.context(id)!!.engine.root.toString()

        // Release the vault's exclusive lock + git/index handles BEFORE responding, so the caller can
        // move/delete the directory immediately.
        vaults.unregister(id)?.close()

        // Persist the removal so it survives a restart. resolvedVaults() materializes a legacy single
        // vaultPath into the explicit list first, so a sibling vault isn't dropped.
        configStore.update { cur ->
            cur.copy(
                vaults = cur.resolvedVaults().filterNot { it.id == id },
                vaultPath = null,
                defaultVault = cur.defaultVaultId(),
            )
        }

        var filesDeleted = false
        if (deleteFiles) {
            deleteRecursively(Paths.get(dir))
            filesDeleted = true
        }
        return DeleteVaultResultDto(id = id, path = dir, filesDeleted = filesDeleted)
    }

    /** Depth-first delete of [dir] and everything under it (no-op if it is already gone). */
    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /** Explicit absolute [CreateVaultRequest.path], else a sibling of an existing vault named [id]. */
    private fun resolvePath(req: CreateVaultRequest, config: SvodConfig): Path {
        req.path?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        val sibling = config.resolvedVaults().firstOrNull()?.let { Paths.get(it.path).parent }
        val parent = sibling ?: Paths.get(System.getProperty("user.home"), ".svod", "vaults")
        return parent.resolve(req.id)
    }

    companion object {
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]*$")
    }
}
