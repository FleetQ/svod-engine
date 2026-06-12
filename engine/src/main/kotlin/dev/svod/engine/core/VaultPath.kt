package dev.svod.engine.core

import java.nio.file.Path

/**
 * A validated, vault-relative path.
 *
 * Invariants enforced at construction:
 *  - uses '/' separators, no leading/trailing slash
 *  - contains no '.' or '..' segments (no traversal)
 *  - does not address an engine-managed top-level directory (.git, .svod, .trash)
 *
 * These checks are the single choke point that keeps every write inside the vault
 * and away from git/engine internals.
 */
@JvmInline
value class VaultPath private constructor(val value: String) {

    fun resolveAgainst(root: Path): Path {
        val resolved = root.resolve(value).normalize()
        val base = root.normalize()
        require(resolved.startsWith(base) && resolved != base) { "path escapes vault: $value" }
        return resolved
    }

    override fun toString(): String = value

    companion object {
        /** Top-level names the engine owns; user paths may never start here. */
        val RESERVED_TOP = setOf(".git", ".svod", ".trash")

        fun of(raw: String): VaultPath {
            require(raw.isNotBlank()) { "path is blank" }
            val norm = raw.replace('\\', '/').trim('/')
            require(norm.isNotEmpty()) { "path resolves to empty" }
            val parts = norm.split('/')
            require(parts.none { it.isBlank() }) { "path has an empty segment: '$raw'" }
            require(parts.none { it == "." || it == ".." }) { "path must not contain '.' or '..': '$raw'" }
            require(parts.first() !in RESERVED_TOP) { "reserved top-level path: '${parts.first()}'" }
            return VaultPath(norm)
        }

        fun ofOrNull(raw: String): VaultPath? = try { of(raw) } catch (_: IllegalArgumentException) { null }
    }
}
