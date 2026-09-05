package dev.svod.engine.api

import dev.svod.engine.core.Author

/** What a person may do in one vault. */
enum class VaultRole {
    /** Read, search, browse history. */
    READER,

    /** Everything a reader can, plus every content mutation (write/delete/move/restore/resolve). */
    EDITOR,
}

/**
 * The authenticated caller of an App API request. Humans reach the App API with a personal key
 * (ADR-0019); the loopback UI without a key is the [local] principal, which keeps every
 * single-user installation working unchanged. [author] flows to git as the commit author, so a
 * shared vault's history names the person, not "svod-ui".
 */
data class Principal(
    val userId: String,
    val author: Author,
    /** Manages the engine: users, agents, backup, vaults, update. Admins reach every vault. */
    val admin: Boolean,
    val grants: Map<String, VaultRole>,
    /** The loopback UI identity (no key presented). Treated as admin. */
    val local: Boolean = false,
) {
    fun canRead(vault: String): Boolean = admin || local || vault in grants
    fun canWrite(vault: String): Boolean = admin || local || grants[vault] == VaultRole.EDITOR

    /** `admin` | `editor` | `reader`, or null when the principal has no access to [vault]. */
    fun roleLabel(vault: String): String? = when {
        admin || local -> "admin"
        grants[vault] == VaultRole.EDITOR -> "editor"
        grants[vault] == VaultRole.READER -> "reader"
        else -> null
    }

    companion object {
        fun local(author: Author): Principal =
            Principal(userId = "local", author = author, admin = true, grants = emptyMap(), local = true)
    }
}

/**
 * Maps personal API keys to principals. Same shape as [dev.svod.engine.mcp.AgentRegistry]: immutable
 * maps swapped atomically on [reload], constant-time key comparison so a wrong key cannot be
 * distinguished from a right one by timing.
 */
class UserRegistry(specs: List<UserSpec>) {

    data class UserSpec(
        val key: String,
        val userId: String,
        val name: String,
        val email: String,
        val admin: Boolean = false,
        val grants: Map<String, VaultRole> = emptyMap(),
    )

    @Volatile private var byKey: Map<String, Principal> = build(specs) { it.key }
    @Volatile private var byId: Map<String, Principal> = build(specs) { it.userId }

    val size: Int get() = byId.size

    /** Swap in a new user set without restarting (build fully, then assign — readers never see a torn state). */
    fun reload(specs: List<UserSpec>) {
        val newByKey = build(specs) { it.key }
        val newById = build(specs) { it.userId }
        byKey = newByKey
        byId = newById
    }

    /** Resolve a bearer key to a principal, or null if unknown. Constant-time over all keys. */
    fun authenticate(key: String?): Principal? {
        if (key.isNullOrEmpty()) return null
        val map = byKey
        var match: Principal? = null
        for ((known, principal) in map) if (constantTimeEquals(known, key)) match = principal
        return match
    }

    fun byUserId(userId: String): Principal? = byId[userId]

    private companion object {
        fun build(specs: List<UserSpec>, keyOf: (UserSpec) -> String): Map<String, Principal> =
            specs.associate { keyOf(it) to Principal(it.userId, Author(it.name, it.email), it.admin, it.grants) }

        fun constantTimeEquals(a: String, b: String): Boolean {
            val ab = a.toByteArray(); val bb = b.toByteArray()
            var diff = ab.size xor bb.size
            for (i in bb.indices) diff = diff or (ab.getOrElse(i) { 0 }.toInt() xor bb[i].toInt())
            return diff == 0
        }
    }
}
