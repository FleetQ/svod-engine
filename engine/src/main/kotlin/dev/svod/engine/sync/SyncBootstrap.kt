package dev.svod.engine.sync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bootstrap a NEW machine into an existing synced vault: clone the canonical sync ref
 * `refs/svod/sync/<vaultId>` from [remote] into an empty [dest], landing it on the local
 * [branch] (`master`) so [dev.svod.engine.core.SvodEngine] opens it as ordinary history (no fresh
 * scaffold root commit — that would diverge it from the fleet). After this, point the engine config
 * at [dest] with `sync.enabled` + the same remote; the machine's stable host id is generated on
 * first boot ([dev.svod.engine.lifecycle.HostIdentity]).
 *
 * This is a one-shot, run-while-the-engine-is-stopped operation (like `svod-engine import`) — a
 * vault cannot be hot-added to a running node — so it is exposed as the `svod-engine clone`
 * subcommand, not an HTTP endpoint.
 */
object SyncBootstrap {

    fun clone(remote: String, dest: Path, vaultId: String, branch: String = "master") {
        require(!Files.exists(dest) || isEmptyDir(dest)) { "destination must be empty or not exist: $dest" }
        Files.createDirectories(dest)

        val repo = FileRepositoryBuilder()
            .setGitDir(dest.resolve(".git").toFile())
            .setWorkTree(dest.toFile())
            .build()
        repo.create(false)
        repo.config.apply {
            setBoolean("core", null, "quotepath", false)
            setBoolean("core", null, "autocrlf", false)
            save()
        }
        Git(repo).use { git ->
            // Fetch the canonical ref straight onto the local branch, then materialize the working tree.
            val fetched = git.fetch().setRemote(remote)
                .setRefSpecs(RefSpec("refs/svod/sync/$vaultId:refs/heads/$branch"))
                .call()
            require(fetched.advertisedRefs.any { it.name == "refs/svod/sync/$vaultId" }) {
                "remote has no canonical sync ref refs/svod/sync/$vaultId — has a machine synced this vault yet?"
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(branch).call()
        }
        repo.close()
    }

    private fun isEmptyDir(dest: Path): Boolean =
        Files.isDirectory(dest) && Files.newDirectoryStream(dest).use { !it.iterator().hasNext() }
}
