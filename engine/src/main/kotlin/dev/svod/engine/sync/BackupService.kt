package dev.svod.engine.sync

import dev.svod.engine.lifecycle.BackupConfig
import dev.svod.engine.lifecycle.BackupConfigStore
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.security.Secrets
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

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
        initialLastBackupAt: String? = null,
        initialLastBackupHead: String? = null,
        initialLastSyncedAt: String? = null,
        initialLastSyncedHead: String? = null,
    ) {
        @Volatile
        var config: SvodConfig.BackupSettings? = initial
            private set

        /** ISO-8601 instant of the last successful backup (null until one succeeds). */
        @Volatile
        var lastBackupAt: String? = initialLastBackupAt
            private set

        /** Commit sha last pushed to the backup ref (null until one succeeds). */
        @Volatile
        var lastBackupHead: String? = initialLastBackupHead
            private set

        /** ISO-8601 instant of the last successful two-way sync (null until one succeeds). */
        @Volatile
        var lastSyncedAt: String? = initialLastSyncedAt
            private set

        /** Commit sha of the local head at the last successful sync (null until one succeeds). */
        @Volatile
        var lastSyncedHead: String? = initialLastSyncedHead
            private set

        /** Guards against two backups of this vault running at once (manual + scheduled). */
        val inFlight = AtomicBoolean(false)

        /** Replace this vault's config (caller rejects inline-credential remotes) and persist it. */
        fun configure(settings: SvodConfig.BackupSettings?) {
            config = settings
            persist()
        }

        /** Record a successful backup (head + ISO-8601 instant) and persist it for the UI/restart. */
        fun recordSuccess(head: String, at: String) {
            lastBackupHead = head
            lastBackupAt = at
            persist()
        }

        /** Record a successful two-way sync (local head + ISO-8601 instant) and persist it. */
        fun recordSyncSuccess(head: String?, at: String) {
            lastSyncedHead = head
            lastSyncedAt = at
            persist()
        }

        private fun persist() {
            val s = config ?: return
            store?.save(BackupConfig(
                remote = s.remote,
                enabled = s.enabled,
                backupOnStartup = s.backupOnStartup,
                backupIntervalMinutes = s.backupIntervalMinutes,
                backupOnChange = s.backupOnChange,
                lastBackupAt = lastBackupAt,
                lastBackupHead = lastBackupHead,
                syncEnabled = s.syncEnabled,
                syncIntervalMinutes = s.syncIntervalMinutes,
                lastSyncedAt = lastSyncedAt,
                lastSyncedHead = lastSyncedHead,
            ))
        }
    }

    // Mutable: a vault created at runtime (POST /api/v1/vaults) must get a binding without a restart,
    // else PUT /settings/backup for it is a silent no-op that still answers 200.
    private val byId = java.util.concurrent.ConcurrentHashMap<String, Binding>(bindings.associateBy { it.id })

    /** Bind a vault hot-added at runtime, so it can be configured and backed up without a restart. */
    fun register(binding: Binding) { byId[binding.id] = binding }

    /** Drop a deleted vault's binding (its scheduler ticks stop with it). */
    fun unregister(vaultId: String) { byId.remove(vaultId) }

    fun configOf(vaultId: String): SvodConfig.BackupSettings? = byId[vaultId]?.config

    /** Set [vaultId]'s backup config; false when no such vault is bound (the caller must not report success). */
    fun configure(vaultId: String, settings: SvodConfig.BackupSettings?): Boolean {
        val binding = byId[vaultId] ?: return false
        binding.configure(settings)
        return true
    }

    /** Every vault id with a backup binding (the scheduler iterates these). */
    fun vaultIds(): Set<String> = byId.keys.toSet()

    /** ISO-8601 instant of [vaultId]'s last successful backup, null until one succeeds. */
    fun lastBackupAt(vaultId: String): String? = byId[vaultId]?.lastBackupAt

    /** Commit sha of [vaultId]'s last successful backup, null until one succeeds. */
    fun lastBackupHead(vaultId: String): String? = byId[vaultId]?.lastBackupHead

    /** True when [vaultId] replicates two-way (sync subsumes one-way backup). */
    fun isSynced(vaultId: String): Boolean = byId[vaultId]?.config?.isSynced() == true

    /** [vaultId]'s sync remote (URL or Secrets ref), null unless it is a synced vault. */
    fun syncRemote(vaultId: String): String? = byId[vaultId]?.config?.takeIf { it.isSynced() }?.remote

    /** [vaultId]'s background sync poll cadence in minutes (default 3 when unset). */
    fun syncIntervalMinutes(vaultId: String): Int = byId[vaultId]?.config?.syncIntervalMinutes?.takeIf { it > 0 } ?: 3

    /** ISO-8601 instant of [vaultId]'s last successful sync, null until one succeeds. */
    fun lastSyncedAt(vaultId: String): String? = byId[vaultId]?.lastSyncedAt

    /** Record a successful sync for [vaultId] (its head + ISO-8601 instant). */
    fun recordSyncSuccess(vaultId: String, head: String?, at: String) { byId[vaultId]?.recordSyncSuccess(head, at) }

    /** True when [vaultId] has never backed up or its last backup is at least [intervalMinutes] old. */
    fun dueForInterval(vaultId: String, intervalMinutes: Int): Boolean = due(byId[vaultId]?.lastBackupAt, intervalMinutes)

    /** True when [vaultId] has never synced or its last sync is at least [intervalMinutes] old. */
    fun dueForSyncInterval(vaultId: String, intervalMinutes: Int): Boolean = due(byId[vaultId]?.lastSyncedAt, intervalMinutes)

    private fun due(last: String?, intervalMinutes: Int): Boolean {
        if (last == null) return true
        return try {
            Duration.between(Instant.parse(last), Instant.now()).toMinutes() >= intervalMinutes
        } catch (_: Exception) {
            true // an unparseable persisted timestamp should not wedge the scheduler
        }
    }

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
        // A synced vault's two-way sync already pushes the canonical ref off-site → one-way backup is
        // retired for it; a manual backup is a graceful no-op (sync is the DR copy).
        if (cfg.isSynced()) return VaultBackup(vaultId, false, b.lastSyncedHead, cfg.remote, "noop")
        // Skip if another backup of this vault is already in flight (a manual click and a scheduled
        // tick must never push the same repo at once); a graceful no-op, not an error.
        if (!b.inFlight.compareAndSet(false, true)) return VaultBackup(vaultId, false, b.lastBackupHead, cfg.remote, "noop")
        try {
            val remote = Secrets.resolve(cfg.remote)
            return SyncGit(b.root).use { git ->
                val branch = git.branch
                val head = git.resolve(branch)
                when {
                    head == null -> VaultBackup(vaultId, false, null, cfg.remote, "noop")
                    // Nothing new since the last successful backup → skip the push.
                    head == b.lastBackupHead -> VaultBackup(vaultId, false, head, cfg.remote, "noop")
                    else -> {
                        // Force-update the mirror backup ref; a non-fast-forward (history rewrite/gc)
                        // overwrites. A transport failure (unreachable / auth) is an expected outcome
                        // → status "error", never a thrown exception bubbling to a 500.
                        val ok = try {
                            git.push(remote, "+refs/heads/$branch:refs/svod/backup/$vaultId")
                        } catch (_: Exception) {
                            false
                        }
                        // Also expose the head on a browsable `main` branch (GitHub/GitFox list only
                        // heads+tags, not refs/svod/*); best-effort, never turns a good backup into an error.
                        if (ok) git.mirrorToBrowsableBranch(remote, branch)
                        if (ok) b.recordSuccess(head, Instant.now().toString())
                        VaultBackup(vaultId, ok, head, cfg.remote, if (ok) "ok" else "error")
                    }
                }
            }
        } finally {
            b.inFlight.set(false)
        }
    }

    /** Back up every vault that has a configured remote (each to its own). */
    suspend fun backupAll(): List<VaultBackup> = byId.keys.map { backupNow(it) }
}
