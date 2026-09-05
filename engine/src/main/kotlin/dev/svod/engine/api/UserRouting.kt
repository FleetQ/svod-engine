package dev.svod.engine.api

/**
 * Runtime management of the people who may reach the App API (ADR-0019). The production
 * implementation lives in [dev.svod.engine.lifecycle.UserController]. Null wiring ⇒ 501.
 */
interface UserAdmin {
    /** Bad userId pattern, blank name, bad role or unknown vault in a grant ⇒ 400. */
    class InvalidRequest(message: String) : Exception(message)
    /** Duplicate userId ⇒ 409. */
    class Conflict(message: String) : Exception(message)
    /** No user with this id ⇒ 404. */
    class UnknownUser(message: String) : Exception(message)

    fun list(): List<UserSpecView>
    /** Creates the user AND its key; the raw key is returned exactly once, here. */
    suspend fun create(req: CreateUserRequest): CreatedUser
    suspend fun update(id: String, req: UpdateUserRequest): UserSpecView
    suspend fun delete(id: String)
    /** Replace the user's key; the old one stops authenticating on the next call. */
    suspend fun rotateKey(id: String): String
}

data class UserSpecView(
    val userId: String,
    val name: String,
    val email: String?,
    val admin: Boolean,
    val grants: List<VaultGrantDto>,
    /** The Secrets ref the key resolves through — never the key itself. */
    val keyRef: String,
)

data class CreatedUser(val user: UserSpecView, val key: String)

/** Stores a secret on the engine host once and hands back a `file:` ref for config fields. */
interface SecretSink {
    class InvalidName(message: String) : Exception(message)
    fun store(name: String, value: String): String
}
