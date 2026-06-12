package dev.svod.engine.security

import java.nio.file.Files
import java.nio.file.Paths

/**
 * Resolves secret references so credentials (agent tokens, keystore passwords) need not sit
 * in plaintext config:
 *  - `env:NAME`   → the `NAME` environment variable,
 *  - `file:/path` → the file's contents (trimmed),
 *  - anything else is treated as a literal value (back-compat).
 *
 * On macOS, a `keychain:` provider belongs in the `dist/` layer (the `security` CLI), keeping
 * the engine OS-agnostic; it plugs in here the same way.
 */
object Secrets {

    fun resolve(ref: String): String = when {
        ref.startsWith("env:") -> System.getenv(ref.removePrefix("env:"))
            ?: throw IllegalStateException("environment secret not set: ${ref.removePrefix("env:")}")
        ref.startsWith("file:") -> Files.readString(Paths.get(ref.removePrefix("file:"))).trim()
        else -> ref
    }
}
