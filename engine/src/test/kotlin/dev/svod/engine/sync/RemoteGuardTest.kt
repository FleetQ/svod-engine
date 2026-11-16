package dev.svod.engine.sync

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteGuardTest {

    private fun ip(s: String) = InetAddress.getByName(s)
    private fun guard(vararg addr: String) = RemoteGuard(true, resolver = { addr.map(::ip) })

    @Test
    fun `every private, loopback, link-local, unique-local, CGNAT and unspecified address is refused`() {
        val blocked = listOf(
            "127.0.0.1", "127.1.2.3", "::1", "10.0.0.5", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.1",
            "169.254.169.254", "169.254.0.1", "fe80::1", "fc00::1", "fd12:3456::1", "100.64.0.1", "100.127.255.254",
            "0.0.0.0", "0.1.2.3", "::", "::ffff:10.0.0.1", "::ffff:127.0.0.1", "::ffff:192.168.0.1", "224.0.0.1",
            "64:ff9b::a00:1", "64:ff9b::8.8.8.8", "64:ff9b:1::1", "64:ff9b:1:ffff::1", "2002:a00:1::1", "2002:808:808::1",
            "2001:0:4136:e378:8000:63bf:3fff:fdd2",
        )
        for (a in blocked) {
            val e = assertFailsWith<RemoteGuard.Refused>("should refuse $a") { guard(a).check("https://git.example.com/r.git") }
            assertEquals("remote host not allowed", e.message, a)
        }
    }

    @Test
    fun `a name that resolves to any blocked address is refused even next to a public one`() {
        assertFailsWith<RemoteGuard.Refused> { guard("93.184.216.34", "10.0.0.5").check("https://git.example.com/r.git") }
    }

    @Test
    fun `public hosts are allowed, including the edges of the blocked ranges`() {
        for (a in listOf("93.184.216.34", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.15.255.255", "172.32.0.1", "192.169.0.1", "2606:4700::1111", "::ffff:8.8.8.8", "2001:db8::1", "2001:1::1", "64:ff9a::1")) {
            guard(a).check("https://git.example.com/r.git")
            guard(a).check("http://git.example.com:3000/org/r.git")
            guard(a).check("https://x-access-token:SECRET@git.example.com/r.git")
        }
    }

    @Test
    fun `IP literals and non-http schemes are refused`() {
        val g = guard("93.184.216.34")
        for (r in listOf(
            "https://93.184.216.34/r.git", "http://127.0.0.1:3000/r.git", "https://[2606:4700::1111]/r.git", "https://u:p@10.0.0.1/r.git",
            "file:///srv/git/r.git", "/srv/git/r.git", "ssh://git@git.example.com/r.git", "git@git.example.com:org/r.git",
            "git://git.example.com/r.git", "ftp://git.example.com/r.git", "env:REMOTE",
        )) {
            assertFailsWith<RemoteGuard.Refused>("should refuse $r") { g.check(r) }
        }
    }

    @Test
    fun `a host that does not resolve is refused with its own message`() {
        val g = RemoteGuard(true, resolver = { throw java.net.UnknownHostException(it) })
        assertEquals("remote host could not be resolved", assertFailsWith<RemoteGuard.Refused> { g.check("https://nope.example/r.git") }.message)
    }

    @Test
    fun `with the guard off nothing is checked`() {
        RemoteGuard.OFF.check("file:///srv/git/r.git")
        RemoteGuard(false, resolver = { error("must not resolve") }).check("http://127.0.0.1/r.git")
    }

    @Test
    fun `redirects are switched off for the transport only in that mode`() {
        val on = Files.createTempDirectory("svod-redir-on-")
        val off = Files.createTempDirectory("svod-redir-off-")
        for (d in listOf(on, off)) Git.init().setDirectory(d.toFile()).call().close()
        SyncGit(on).close()
        SyncGit(off, followRedirects = false).close()
        fun setting(d: java.nio.file.Path) = Git.open(d.toFile()).use { it.repository.config.getString("http", null, "followRedirects") }
        assertNull(setting(on))
        assertEquals("false", setting(off))
        SyncGit(off).close()
        assertNull(setting(off), "turning denyPrivateRemotes off again removes the pinned setting")
    }

    // ---- through the sync engine ----

    private class Rig(guard: RemoteGuard) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("svod-guard-")
        val engine = SvodEngine.open(dir, scope, dev.svod.engine.security.SecretScanner.OFF, Author("m", "m@svod.local"))
        val sync = SyncEngine(engine, SyncGit(dir), ConflictStore(), EventBus(), "v", "machine", guard = guard)
        override fun close() = engine.close()
    }

    @Test
    fun `a refused remote is a normal failed cycle with a redacted lastError, checked again every cycle`() = runBlocking {
        var calls = 0
        val rebinding = RemoteGuard(true, resolver = { calls++; listOf(ip(if (calls == 1) "10.0.0.5" else "127.0.0.1")) })
        Rig(rebinding).use { r ->
            val remote = "https://x-access-token:SECRET@git.example.com/r.git"
            assertEquals(SyncEngine.Status.offline, r.sync.sync(remote).status)
            assertEquals("remote check failed: remote host not allowed", r.sync.lastError)
            assertEquals(SyncEngine.Status.offline, r.sync.sync(remote).status)
            assertEquals(2, calls, "the host is resolved again on the second cycle")
            assertFalse("SECRET" in r.sync.lastError!!)
        }
    }

    @Test
    fun `a failing https remote gives a lastError without the token, and a good cycle clears it`() = runBlocking {
        Rig(RemoteGuard.OFF).use { r ->
            val res = r.sync.sync("https://x-access-token:SECRET@127.0.0.1:1/r.git")
            assertEquals(SyncEngine.Status.offline, res.status)
            val err = assertNotNull(r.sync.lastError)
            assertTrue(err.startsWith("fetch failed: "), err)
            assertFalse("SECRET" in err, err)
            assertFalse("x-access-token" in err, err)

            val bare = Files.createTempDirectory("svod-bare-").also { Git.init().setBare(true).setDirectory(it.toFile()).call().close() }
            assertEquals(SyncEngine.Status.inSync, r.sync.sync(bare.toString()).status)
            assertNull(r.sync.lastError, "cleared by a cycle that ends in sync")
        }
    }

    @Test
    fun `a secret in the failure text is scrubbed even when the transport echoes it outside a URL`() = runBlocking {
        class Echo(root: java.nio.file.Path) : SyncGit(root) {
            override fun fetchSync(remote: String, vaultId: String) = throw RuntimeException("auth failed for token SECRETTOKEN on $remote")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("svod-echo-")
        SvodEngine.open(dir, scope, dev.svod.engine.security.SecretScanner.OFF, Author("m", "m@svod.local")).use { engine ->
            val sync = SyncEngine(engine, Echo(dir), ConflictStore(), EventBus(), "v", "machine")
            sync.sync("https://user:SECRETTOKEN@git.example.com/r.git")
            val err = sync.lastError!!
            assertFalse("SECRETTOKEN" in err, err)
        }
    }
}
