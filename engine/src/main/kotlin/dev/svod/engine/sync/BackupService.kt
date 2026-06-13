package dev.svod.engine.sync

import dev.svod.engine.lifecycle.BackupConfig
import dev.svod.engine.lifecycle.BackupConfigStore
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.security.Secrets
import java.nio.file.Path

/**
 * Off-site backup (disaster recovery), **per vault**: each vault pushes its canonical branch to its
 * OWN configured remote under `refs/svod/backup/<vaultId>` — so a work vault can back up to a
 * company server and a personal vault to your own, matching the "different environments" model. The
 * backup ref is a flat, per-vault namespace that never collides with the sync namespaces (heads and
 * `refs/svod/<host>`). Backup is push-only and read-only against the local repo (its own jgit handle
 * per vault, like [SyncGit]), so it never races a vault's single writer.
 *
 * A remote is resolved through [Secrets] only at push time (credentials never inlined; a remote that
 * embeds a password is rejected upstream). A runtime change (PUT /api/v1/settings/backup) persists to
 * that vault's [BackupConfigStore] so it survives a restart. Disabled/unconfigured backup is a no-op.
 */
class BackupService(bindings: List<Binding>) {

    /** Per-vault backup binding: the vault's id + repo root, its current config, and its persistence. */
    class Binding(
        val id: String,
        val root: Path,
        initial: SvodConfig.BackupSettings?,
        private val store: BackupConfigStore?,
    ) {
        @Volatile
        var config: SvodConfig.BackupSettings? = initial
            private set

        /** Replace this vault's destination (caller rejects inline-credential remotes) and persist it. */
        fun configure(settings: SvodConfig.BackupSettings?) {
            config = settings
            if (store != null && settings != null) store.save(BackupConfig(settings.remote, settings.enabled))
        }
    }

    private val byId: Map<String, Binding> = bindings.associateBy { it.id }

    fun configOf(vaultId: String): SvodConfig.BackupSettings? = byId[vaultId]?.config
    fun configure(vaultId: String, settings: SvodConfig.BackupSettings?) { byId[vaultId]?.configure(settings) }

    /** One vault's backup outcome. [status] ∈ ok | noop | disabled | unknown_vault | error. */
    data class VaultBackup(
        val vaultId: String,
        val pushed: Boolean,
        val head: String?,
        val remote: String?,
        val status: String,
    )

    /** Back up ONE vault to its own remote. No-op when that vault has no enabled backup remote. */
    suspend fun backupNow(vaultId: String): VaultBackup {
        val b = byId[vaultId] ?: return VaultBackup(vaultId, false, null, null, "unknown_vault")
        val cfg = b.config
        if (cfg == null || !cfg.enabled) return VaultBackup(vaultId, false, null, cfg?.remote, "disabled")
        val remote = Secrets.resolve(cfg.remote)
        return SyncGit(b.root).use { git ->
            val branch = git.branch
            val head = git.resolve(branch)
            if (head == null) {
                VaultBackup(vaultId, false, null, cfg.remote, "noop")
            } else {
                // Force-update the mirror backup ref; a non-fast-forward (history rewrite/gc) overwrites.
                val ok = git.push(remote, "+refs/heads/$branch:refs/svod/backup/$vaultId")
                VaultBackup(vaultId, ok, head, cfg.remote, if (ok) "ok" else "error")
            }
        }
    }

    /** Back up every vault that has a configured remote (each to its own). */
    suspend fun backupAll(): List<VaultBackup> = byId.keys.map { backupNow(it) }
}
