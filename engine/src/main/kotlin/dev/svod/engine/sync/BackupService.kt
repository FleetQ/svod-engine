package dev.svod.engine.sync

import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.security.Secrets
import java.nio.file.Path

/**
 * Off-site backup (disaster recovery): pushes every vault's canonical branch to the configured
 * backup remote under `refs/svod/backup/<vaultId>` — a flat, per-vault namespace that never
 * collides with the sync namespaces (heads and `refs/svod/<host>`). Backup is push-only and
 * read-only against the local repo (it opens its own jgit handle per vault, like [SyncGit]), so it
 * never races a vault's single writer.
 *
 * The remote URL is resolved through [Secrets] at push time so credentials are never inlined in
 * config; a remote that embeds a password inline is rejected upstream (config validation + the
 * settings endpoint). Disabled or unconfigured backup is a graceful no-op.
 */
class BackupService(
    private val vaults: List<VaultRef>,
    config: SvodConfig.BackupSettings?,
) {
    /**
     * The active destination. Settable at runtime via the App API (PUT /api/v1/settings/backup);
     * the new value takes effect for the next [backupNow] in this process. Persisting it to the
     * config file is a `dist/` concern, out of this layer's scope.
     */
    @Volatile
    var config: SvodConfig.BackupSettings? = config
        private set

    /** Replace the destination after validation (caller rejects inline-credential remotes). */
    fun configure(settings: SvodConfig.BackupSettings?) { config = settings }

    /** Just enough of a vault to back it up: its id and the on-disk git repo root. */
    data class VaultRef(val id: String, val root: Path)

    /** One vault's backup outcome. [pushed] false ⇒ skipped (disabled) or nothing to push. */
    data class VaultBackup(val vaultId: String, val pushed: Boolean, val head: String?)

    /** Result of [backupNow]: per-vault outcomes plus the (redacted) remote it targeted. */
    data class BackupResult(
        val enabled: Boolean,
        val remote: String?,
        val vaults: List<VaultBackup>,
    ) {
        /** True when at least one vault's branch was pushed to the backup remote. */
        val pushed: Boolean get() = vaults.any { it.pushed }
        /** The newest head pushed (for a single ack head; all vaults share one remote). */
        val head: String? get() = vaults.firstOrNull { it.pushed }?.head ?: vaults.firstOrNull()?.head
    }

    /**
     * Push every vault's canonical branch to the backup remote. No-op (enabled=false) when backup
     * is unconfigured or disabled. The remote is resolved through [Secrets] only here, never stored.
     */
    suspend fun backupNow(): BackupResult {
        val cfg = config
        if (cfg == null || !cfg.enabled) {
            return BackupResult(enabled = false, remote = null, vaults = emptyList())
        }
        val remote = Secrets.resolve(cfg.remote)
        val outcomes = vaults.map { v ->
            SyncGit(v.root).use { git ->
                val branch = git.branch
                val head = git.resolve(branch)
                if (head == null) {
                    VaultBackup(v.id, pushed = false, head = null)
                } else {
                    // Force-update the backup ref to the current canonical branch; the backup ref is
                    // a mirror, so a non-fast-forward (history rewrite/gc) must still overwrite it.
                    val ok = git.push(remote, "+refs/heads/$branch:refs/svod/backup/${v.id}")
                    VaultBackup(v.id, pushed = ok, head = head)
                }
            }
        }
        return BackupResult(enabled = true, remote = cfg.remote, vaults = outcomes)
    }
}
