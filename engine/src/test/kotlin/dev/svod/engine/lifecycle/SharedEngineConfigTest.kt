package dev.svod.engine.lifecycle

import kotlin.test.Test
import kotlin.test.assertTrue

/** ADR-0019: the config rules that keep a shared engine from being exposed unauthenticated or unencrypted. */
class SharedEngineConfigTest {

    private val tls = SvodConfig.TlsSettings("/tmp/ks.p12", "pw", "svod", "pw")
    private val admin = SvodConfig.UserSettings("boss", "Boss", keyRef = "file:/tmp/boss.key", admin = true)

    private fun base() = SvodConfig(vaults = listOf(SvodConfig.VaultSettings("a", "/tmp/a")))

    @Test
    fun `leaving loopback needs App API TLS, MCP TLS and at least one user`() {
        val bare = base().copy(host = "0.0.0.0").validate()
        assertTrue(bare.any { "appApiTls is required" in it }, bare.toString())
        assertTrue(bare.any { "mcpTls is required" in it }, bare.toString())
        assertTrue(bare.any { "at least one user" in it }, bare.toString())

        val ok = base().copy(host = "0.0.0.0", appApiTls = tls, mcpTls = tls, users = listOf(admin)).validate()
        assertTrue(ok.isEmpty(), ok.toString())
    }

    @Test
    fun `localAdmin off without users is rejected`() {
        val errors = base().copy(localAdmin = false).validate()
        assertTrue(errors.any { "localAdmin=false requires" in it }, errors.toString())
        assertTrue(base().copy(localAdmin = false, users = listOf(admin)).validate().isEmpty())
    }

    @Test
    fun `user records are validated`() {
        val errors = base().copy(users = listOf(
            admin,
            admin.copy(userId = "boss"),                                                            // duplicate id + keyRef
            SvodConfig.UserSettings("Bad Id", "", keyRef = " ", grants = listOf(SvodConfig.VaultGrant("zzz", "owner"))),
            SvodConfig.UserSettings("twice", "T", keyRef = "env:T", grants = listOf(SvodConfig.VaultGrant("a", "reader"), SvodConfig.VaultGrant("a", "editor"))),
        )).validate()
        assertTrue(errors.any { "user ids must be unique" in it }, errors.toString())
        assertTrue(errors.any { "keyRefs must be unique" in it }, errors.toString())
        assertTrue(errors.any { "keyRefs must be non-blank" in it }, errors.toString())
        assertTrue(errors.any { "must match" in it }, errors.toString())
        assertTrue(errors.any { "must have a name" in it }, errors.toString())
        assertTrue(errors.any { "unknown vault 'zzz'" in it }, errors.toString())
        assertTrue(errors.any { "role must be one of" in it }, errors.toString())
        assertTrue(errors.any { "grants a vault twice" in it }, errors.toString())
    }

    @Test
    fun `users round-trip through JSON with defaults`() {
        val cfg = base().copy(users = listOf(admin.copy(grants = listOf(SvodConfig.VaultGrant("a", "EDITOR")))))
        val back = SvodConfig.Companion.let { c -> c.toJson(cfg) }.let { json ->
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(SvodConfig.serializer(), json)
        }
        assertTrue(back.localAdmin)
        assertTrue(back.users.single().admin)
        assertTrue(back.users.single().grants.single().role == "EDITOR")
    }
}
