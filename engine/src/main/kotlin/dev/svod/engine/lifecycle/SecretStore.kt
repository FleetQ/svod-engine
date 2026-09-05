package dev.svod.engine.lifecycle

import dev.svod.engine.api.SecretSink
import dev.svod.engine.security.SecretFiles
import java.nio.file.Path

/**
 * `POST /api/v1/secrets`: a secret entered once in a remote UI (a GitHub token for the company
 * repo, an embedder API key) lands on the engine host as `<dir>/<name>.secret` (0600) and the
 * caller gets back the `file:` ref to use in config fields. The value never appears in a response.
 */
class SecretStore(private val dir: Path) : SecretSink {

    override fun store(name: String, value: String): String {
        if (!NAME.matches(name)) throw SecretSink.InvalidName("secret name must match ${NAME.pattern}, was '$name'")
        if (value.isBlank()) throw SecretSink.InvalidName("secret value must be non-blank")
        val path = dir.resolve("$name.secret")
        SecretFiles.write(path, value)
        return "file:${path.toAbsolutePath()}"
    }

    companion object {
        val NAME = Regex("^[a-z0-9][a-z0-9_.-]*$")
    }
}
