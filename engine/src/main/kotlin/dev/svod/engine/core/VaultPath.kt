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

        /**
         * Reserved top-level names that still occur in a vault's git history: a soft delete moves a
         * note under `.trash/` and commits it, on any machine, so a sync merge carries `.trash/` paths.
         * `.svod/` is git-ignored engine state local to one machine and `.git` is never a tree entry;
         * neither is ever taken from a peer.
         */
        val HISTORY_TOP = setOf(".trash")

        fun of(raw: String): VaultPath = parse(raw, RESERVED_TOP)

        /**
         * A path taken from the vault's own git history (a sync merge), not from a user: `.trash/` is
         * accepted, `.git` and `.svod` are refused as in [of]. User writes keep going through [of].
         */
        fun ofHistory(raw: String): VaultPath = parse(raw, RESERVED_TOP - HISTORY_TOP)

        fun ofHistoryOrNull(raw: String): VaultPath? = try { ofHistory(raw) } catch (_: IllegalArgumentException) { null }

        private fun parse(raw: String, reserved: Set<String>): VaultPath {
            require(raw.isNotBlank()) { "path is blank" }
            val norm = raw.replace('\\', '/').trim('/')
            require(norm.isNotEmpty()) { "path resolves to empty" }
            val parts = norm.split('/')
            require(parts.none { it.isBlank() }) { "path has an empty segment: '$raw'" }
            require(parts.none { it == "." || it == ".." }) { "path must not contain '.' or '..': '$raw'" }
            require(parts.first() !in reserved) { "reserved top-level path: '${parts.first()}'" }
            return VaultPath(norm)
        }

        fun ofOrNull(raw: String): VaultPath? = try { of(raw) } catch (_: IllegalArgumentException) { null }
    }
}
