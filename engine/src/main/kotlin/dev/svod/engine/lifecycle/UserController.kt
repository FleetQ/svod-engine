package dev.svod.engine.lifecycle

import dev.svod.engine.api.CreateUserRequest
import dev.svod.engine.api.CreatedUser
import dev.svod.engine.api.UpdateUserRequest
import dev.svod.engine.api.UserAdmin
import dev.svod.engine.api.UserRegistry
import dev.svod.engine.api.UserSpecView
import dev.svod.engine.api.VaultGrantDto
import dev.svod.engine.security.SecretFiles
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/**
 * Runtime creation, update, deletion and key rotation of App API users (ADR-0019). Mirrors
 * [AgentController]: config is persisted via [configStore], the in-memory [registry] is reloaded
 * atomically after every mutation, so a new or rotated key authenticates on the very next call and
 * a revoked one fails on it — no restart.
 *
 * Keys are generated HERE (not chosen by the caller), written to `<secretsDir>/user-<id>.key` with
 * 0600, and referenced from config as `file:`. The raw key leaves the engine exactly once: in the
 * create / rotate response.
 */
class UserController(
    private val configStore: ConfigStore,
    private val registry: UserRegistry,
    private val secretsDir: Path,
) : UserAdmin {

    override fun list(): List<UserSpecView> = configStore.config.users.map { it.toView() }

    override suspend fun create(req: CreateUserRequest): CreatedUser {
        val id = req.userId
        if (!ID_PATTERN.matches(id)) throw UserAdmin.InvalidRequest("invalid userId '$id' (must match ${ID_PATTERN.pattern})")
        if (req.name.isBlank()) throw UserAdmin.InvalidRequest("name must be non-blank")
        validateGrants(req.grants)
        if (configStore.config.users.any { it.userId == id }) throw UserAdmin.Conflict("user id already exists: $id")

        val key = newKey()
        val keyFile = keyFile(id)
        SecretFiles.write(keyFile, key)
        val new = SvodConfig.UserSettings(
            userId = id,
            name = req.name.trim(),
            email = req.email?.trim()?.takeIf { it.isNotEmpty() },
            keyRef = "file:${keyFile.toAbsolutePath()}",
            admin = req.admin,
            grants = req.grants.map { SvodConfig.VaultGrant(it.vault, it.role.uppercase()) },
        )
        configStore.update { it.copy(users = it.users + new) }
        registry.reload(configStore.config.toUserSpecs())
        return CreatedUser(new.toView(), key)
    }

    override suspend fun update(id: String, req: UpdateUserRequest): UserSpecView {
        val existing = configStore.config.users.firstOrNull { it.userId == id }
            ?: throw UserAdmin.UnknownUser("unknown user: $id")
        req.name?.let { if (it.isBlank()) throw UserAdmin.InvalidRequest("name must be non-blank") }
        req.grants?.let { validateGrants(it) }
        val updated = existing.copy(
            name = req.name?.trim() ?: existing.name,
            email = req.email?.trim() ?: existing.email,
            admin = req.admin ?: existing.admin,
            grants = req.grants?.map { SvodConfig.VaultGrant(it.vault, it.role.uppercase()) } ?: existing.grants,
        )
        configStore.update { cfg -> cfg.copy(users = cfg.users.map { if (it.userId == id) updated else it }) }
        registry.reload(configStore.config.toUserSpecs())
        return updated.toView()
    }

    override suspend fun delete(id: String) {
        val existing = configStore.config.users.firstOrNull { it.userId == id }
            ?: throw UserAdmin.UnknownUser("unknown user: $id")
        configStore.update { it.copy(users = it.users.filterNot { u -> u.userId == id }) }
        registry.reload(configStore.config.toUserSpecs())
        // Only our own key files are removed — a keyRef pointing elsewhere (env:, an operator's file) is left alone.
        if (existing.keyRef.startsWith("file:")) {
            val p = Path.of(existing.keyRef.removePrefix("file:"))
            if (SecretFiles.isInside(p, secretsDir)) runCatching { Files.deleteIfExists(p) }
        }
    }

    override suspend fun rotateKey(id: String): String {
        val existing = configStore.config.users.firstOrNull { it.userId == id }
            ?: throw UserAdmin.UnknownUser("unknown user: $id")
        val key = newKey()
        val keyFile = keyFile(id)
        SecretFiles.write(keyFile, key)
        val ref = "file:${keyFile.toAbsolutePath()}"
        if (existing.keyRef != ref) {
            configStore.update { cfg -> cfg.copy(users = cfg.users.map { if (it.userId == id) it.copy(keyRef = ref) else it }) }
        }
        registry.reload(configStore.config.toUserSpecs())
        return key
    }

    private fun validateGrants(grants: List<VaultGrantDto>) {
        val known = configStore.config.resolvedVaults().map { it.id }.toSet()
        for (g in grants) {
            if (g.vault !in known) throw UserAdmin.InvalidRequest("grant names unknown vault '${g.vault}'")
            if (SvodConfig.USER_ROLES.none { it.equals(g.role, ignoreCase = true) }) {
                throw UserAdmin.InvalidRequest("role must be one of ${SvodConfig.USER_ROLES}, was '${g.role}'")
            }
        }
        if (grants.map { it.vault }.let { it.size != it.toSet().size }) throw UserAdmin.InvalidRequest("a vault may appear once per user")
    }

    private fun keyFile(id: String): Path = secretsDir.resolve("user-$id.key")

    private fun SvodConfig.UserSettings.toView() = UserSpecView(
        userId = userId, name = name, email = email, admin = admin,
        grants = grants.map { VaultGrantDto(it.vault, it.role.lowercase()) },
        keyRef = keyRef,
    )

    companion object {
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]*$")
        private val random = SecureRandom()

        /** 256 random bits, base64url, `svk_`-prefixed so a leaked key is recognisable in a scan. */
        fun newKey(): String {
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            return "svk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
