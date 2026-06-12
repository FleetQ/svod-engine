package dev.svod.engine.security

import java.nio.file.Files
import java.nio.file.Paths

/**
 * Resolves secret references so credentials (agent tokens, keystore passwords) need not sit
 * in plaintext config:
 *  - `env:NAME`              → the `NAME` environment variable,
 *  - `file:/path`            → the file's contents (trimmed),
 *  - `keychain:[service/]account` → a macOS Keychain generic password (service defaults to
 *                             `svod`), read via the `security` CLI,
 *  - anything else is treated as a literal value (back-compat).
 *
 * `keychain:` is macOS-only by nature; on other platforms (or if the item is missing) it
 * throws, so it is only used where the deployment actually provides it.
 */
object Secrets {

    private const val DEFAULT_KEYCHAIN_SERVICE = "svod"

    fun resolve(ref: String): String = when {
        ref.startsWith("env:") -> System.getenv(ref.removePrefix("env:"))
            ?: throw IllegalStateException("environment secret not set: ${ref.removePrefix("env:")}")
        ref.startsWith("file:") -> Files.readString(Paths.get(ref.removePrefix("file:"))).trim()
        ref.startsWith("keychain:") -> keychain(ref.removePrefix("keychain:"))
        else -> ref
    }

    /** [spec] is `service/account` or just `account` (service defaults to `svod`). */
    private fun keychain(spec: String): String {
        val service = if ('/' in spec) spec.substringBefore('/') else DEFAULT_KEYCHAIN_SERVICE
        val account = spec.substringAfter('/')
        require(account.isNotBlank()) { "keychain ref must include an account: '$spec'" }
        val proc = ProcessBuilder("security", "find-generic-password", "-s", service, "-a", account, "-w")
            .redirectErrorStream(false)
            .start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        val code = proc.waitFor()
        check(code == 0) { "keychain secret not found: service='$service' account='$account'" }
        return out
    }
}
