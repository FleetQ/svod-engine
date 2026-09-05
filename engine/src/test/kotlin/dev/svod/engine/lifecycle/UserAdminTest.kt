package dev.svod.engine.lifecycle

import dev.svod.engine.api.CreateUserRequest
import dev.svod.engine.api.SecretSink
import dev.svod.engine.api.UpdateUserRequest
import dev.svod.engine.api.UserAdmin
import dev.svod.engine.api.UserRegistry
import dev.svod.engine.api.VaultGrantDto
import dev.svod.engine.api.VaultRole
import dev.svod.engine.security.Secrets
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserAdminTest {

    private fun controller(localAdmin: Boolean = true): Triple<UserController, UserRegistry, Path> {
        val dir = Files.createTempDirectory("svod-users-")
        val config = SvodConfig(vaults = listOf(
            SvodConfig.VaultSettings("a", dir.resolve("a").toString()),
            SvodConfig.VaultSettings("b", dir.resolve("b").toString()),
        ), localAdmin = localAdmin)
        val registry = UserRegistry(emptyList())
        return Triple(UserController(ConfigStore(config, null), registry, dir.resolve("secrets")), registry, dir.resolve("secrets"))
    }

    private fun isPosix() = Files.getFileStore(Files.createTempDirectory("svod-posix-")).supportsFileAttributeView("posix")

    @Test
    fun `create issues a key once, stores it 0600, and the registry accepts it without a restart`(): Unit = runBlocking {
        val (ctrl, registry, secrets) = controller()
        val created = ctrl.create(CreateUserRequest("maria", "Мария", grants = listOf(VaultGrantDto("a", "editor"))))
        assertTrue(created.key.startsWith("svk_"), created.key)
        assertTrue(created.user.keyRef.startsWith("file:"), created.user.keyRef)
        val keyFile = secrets.resolve("user-maria.key")
        assertTrue(Files.isRegularFile(keyFile))
        assertEquals(created.key, Secrets.resolve(created.user.keyRef))
        if (isPosix()) assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(keyFile))
        val p = assertNotNull(registry.authenticate(created.key))
        assertEquals("maria", p.userId)
        assertEquals("Мария", p.author.name)
        assertEquals(VaultRole.EDITOR, p.grants["a"])
        assertFalse(p.admin)
        assertFalse(ctrl.list().toString().contains(created.key), "list must not expose the key")
    }

    @Test
    fun `the last admin cannot be deleted or demoted while localAdmin is off`(): Unit = runBlocking {
        val (ctrl, registry, _) = controller(localAdmin = false)
        val boss = ctrl.create(CreateUserRequest("boss", "Boss", admin = true))
        ctrl.create(CreateUserRequest("maria", "Мария"))
        assertFailsWith<UserAdmin.InvalidRequest> { ctrl.delete("boss") }
        assertFailsWith<UserAdmin.InvalidRequest> { ctrl.update("boss", UpdateUserRequest(admin = false)) }
        assertNotNull(registry.authenticate(boss.key), "the refused delete must not have touched the registry")
        // A second admin makes the first one removable.
        ctrl.update("maria", UpdateUserRequest(admin = true))
        ctrl.delete("boss")
        assertNull(registry.authenticate(boss.key))
    }

    @Test
    fun `an empty email clears it and the git author falls back to the synthetic one`(): Unit = runBlocking {
        val (ctrl, registry, _) = controller()
        val u = ctrl.create(CreateUserRequest("ivan", "Иван", email = "ivan@co"))
        assertEquals("ivan@co", registry.authenticate(u.key)!!.author.email)
        ctrl.update("ivan", UpdateUserRequest(email = ""))
        assertNull(ctrl.list().single().email)
        assertEquals("ivan@users.svod.local", registry.authenticate(u.key)!!.author.email)
    }

    @Test
    fun `rotate replaces the key at once`(): Unit = runBlocking {
        val (ctrl, registry, _) = controller()
        val first = ctrl.create(CreateUserRequest("ivan", "Иван")).key
        val second = ctrl.rotateKey("ivan")
        assertNull(registry.authenticate(first))
        assertNotNull(registry.authenticate(second))
    }

    @Test
    fun `delete revokes the key and removes its file`(): Unit = runBlocking {
        val (ctrl, registry, secrets) = controller()
        val key = ctrl.create(CreateUserRequest("tmp", "Temp")).key
        ctrl.delete("tmp")
        assertNull(registry.authenticate(key))
        assertFalse(Files.exists(secrets.resolve("user-tmp.key")))
        assertFailsWith<UserAdmin.UnknownUser> { ctrl.delete("tmp") }
    }

    @Test
    fun `validation - bad id, bad role, unknown vault, duplicate`(): Unit = runBlocking {
        val (ctrl, _, _) = controller()
        assertFailsWith<UserAdmin.InvalidRequest> { ctrl.create(CreateUserRequest("Bad Id", "x")) }
        assertFailsWith<UserAdmin.InvalidRequest> { ctrl.create(CreateUserRequest("ok", "x", grants = listOf(VaultGrantDto("a", "owner")))) }
        assertFailsWith<UserAdmin.InvalidRequest> { ctrl.create(CreateUserRequest("ok", "x", grants = listOf(VaultGrantDto("zzz", "reader")))) }
        assertFailsWith<UserAdmin.InvalidRequest> { ctrl.create(CreateUserRequest("ok", " ")) }
        ctrl.create(CreateUserRequest("dup", "Dup"))
        assertFailsWith<UserAdmin.Conflict> { ctrl.create(CreateUserRequest("dup", "Dup2")) }
    }

    @Test
    fun `update keeps omitted fields and reloads the registry`(): Unit = runBlocking {
        val (ctrl, registry, _) = controller()
        val key = ctrl.create(CreateUserRequest("ann", "Ann", email = "ann@co", grants = listOf(VaultGrantDto("a", "reader")))).key
        val v = ctrl.update("ann", UpdateUserRequest(grants = listOf(VaultGrantDto("a", "editor"), VaultGrantDto("b", "reader")), admin = true))
        assertEquals("Ann", v.name)
        assertEquals("ann@co", v.email)
        assertTrue(v.admin)
        val p = assertNotNull(registry.authenticate(key))
        assertEquals(VaultRole.EDITOR, p.grants["a"])
        assertEquals(VaultRole.READER, p.grants["b"])
        assertTrue(p.admin)
        assertFailsWith<UserAdmin.UnknownUser> { ctrl.update("nobody", UpdateUserRequest(name = "x")) }
    }

    @Test
    fun `secret store writes a 0600 file and returns a resolvable ref`() {
        val dir = Files.createTempDirectory("svod-secrets-")
        val store = SecretStore(dir.resolve("secrets"))
        val ref = store.store("backup-work", "https://x-access-token:ghp_abc@github.com/org/repo.git")
        assertTrue(ref.startsWith("file:"), ref)
        assertEquals("https://x-access-token:ghp_abc@github.com/org/repo.git", Secrets.resolve(ref))
        if (isPosix()) assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(Path.of(ref.removePrefix("file:"))))
        assertFailsWith<SecretSink.InvalidName> { store.store("../etc/passwd", "x") }
        assertFailsWith<SecretSink.InvalidName> { store.store("ok", " ") }
    }
}
