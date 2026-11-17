package dev.svod.engine.lifecycle

import dev.svod.engine.api.SecretSink
import dev.svod.engine.security.SecretFiles
import java.nio.file.Path

/**
 * `POST /api/v1/secrets`: a secret entered once in a remote UI (a GitHub token for the company
 * repo, an embedder API key) lands on the engine host as `<dir>/<name>.secret` (0600) and the
 * caller gets back the `file:` ref to use in config fields. The value never appears in a response.
 * The name states the purpose: on a hosted engine (`denyPrivateRemotes`) an embedder key must be named
 * `embedder-…` and a git remote secret `git-remote-…`, so neither can be pointed at the other ([owns]).
 */
class SecretStore(private val dir: Path) : SecretSink {

    override fun store(name: String, value: String): String {
        if (!NAME.matches(name)) throw SecretSink.InvalidName("secret name must match ${NAME.pattern}, was '$name'")
        if (value.isBlank()) throw SecretSink.InvalidName("secret value must be non-blank")
        val path = dir.resolve("$name.secret")
        SecretFiles.write(path, value)
        return "file:${path.toAbsolutePath()}"
    }

    override fun owns(ref: String, namePrefix: String): Boolean {
        if (!ref.startsWith("file:")) return false
        val raw = ref.removePrefix("file:")
        if (".." in raw) return false
        return try {
            // toRealPath follows every symlink, so a link that points outside the directory, or at a secret
            // with another purpose, is judged by where it really lands.
            val real = java.nio.file.Paths.get(raw).toRealPath()
            val name = real.fileName.toString()
            real.parent == dir.toRealPath() && name.endsWith(".secret") &&
                name.removeSuffix(".secret").let { it.startsWith(namePrefix) && NAME.matches(it) } &&
                java.nio.file.Files.isRegularFile(real, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        val NAME = Regex("^[a-z0-9][a-z0-9_.-]*$")
        /** Name prefix of an embedder API key secret (the only kind an embedder may use on a hosted engine). */
        const val EMBEDDER_PREFIX = "embedder-"
        /** Name prefix of a git remote secret (svod-web stores `git-remote-main`). */
        const val GIT_REMOTE_PREFIX = "git-remote-"
    }
}
