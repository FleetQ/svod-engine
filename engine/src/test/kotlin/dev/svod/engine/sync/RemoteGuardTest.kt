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
            "::127.0.0.1", "::10.0.0.1", "::169.254.169.254", "::192.168.1.1",
            "198.18.0.1", "198.19.255.254", "240.0.0.1", "250.1.2.3", "255.255.255.254", "255.255.255.255",
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
        for (a in listOf("93.184.216.34", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.15.255.255", "172.32.0.1", "192.169.0.1", "2606:4700::1111", "::ffff:8.8.8.8", "2001:db8::1", "2001:1::1", "64:ff9a::1",
            "::8.8.8.8", "198.17.255.255", "198.20.0.1", "223.255.255.254")) {
            guard(a).check("https://git.example.com/r.git")
            guard(a).check("https://git.example.com:3000/org/r.git")
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
    fun `remotes that parsers read differently, numeric hosts and plain http are refused before any lookup`() {
        val g = RemoteGuard(true, resolver = { error("must not resolve $it") })
        for (r in listOf(
            "https://evil@127.0.0.1@github.com/x", "https://github.com#@127.0.0.1/x", "https://127.0.0.1\\@github.com/x",
            "https://github.com\\@127.0.0.1/x", "https://user:p@ss@github.com/x", "https://a?b@github.com/x",
            "http://2130706433/", "https://2130706433/", "http://0177.0.0.1/", "https://0177.0.0.1/", "https://0x7f.0.0.1/",
            "https://0x7f000001/", "https://127.1/", "https://[::ffff:127.0.0.1]/x", "https://[::1]/x",
            "https://github.com./x", "https://localhost./x", "https://github.com/x#frag", "https://git hub.com/x",
            "http://git.example.com/r.git", "HTTP://git.example.com/r.git", "https:///r.git", "https://-bad.example.com/x",
            "https://exa_mple.com/x", "https://git.example.123/x",
        )) {
            assertFailsWith<RemoteGuard.Refused>("should refuse $r") { g.check(r) }
        }
        assertEquals("github.com", RemoteGuard.hostOf("https://x-access-token:tok@GitHub.com/org/r.git"))
        assertEquals("git.example.co", RemoteGuard.hostOf("https://git.example.co:8443/r.git?x=1"))
    }

    @Test
    fun `the TLS peer check refuses a blocked address and leaves others to the normal trust manager`() {
        java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            java.net.Socket(InetAddress.getLoopbackAddress(), server.localPort).use { sock ->
                val inner = object : javax.net.ssl.X509TrustManager {
                    var called = false
                    override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                    override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) { called = true }
                    override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
                }
                val e = assertFailsWith<java.security.cert.CertificateException> { PinnedHttps.PeerCheck(inner).checkServerTrusted(arrayOf(), "RSA", sock) }
                assertEquals("remote peer address not allowed", e.message)
                assertFalse(inner.called, "nothing is trusted for a blocked peer")
            }
        }
    }

    @Test
    fun `with the pin on a JGit https fetch to a loopback server fails in the handshake`() {
        val ks = io.ktor.network.tls.certificates.buildKeyStore {
            certificate("t") { password = "changeit"; domains = listOf("localhost") }
        }
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as javax.net.ssl.SSLServerSocket
        val acceptor = Thread {
            while (!server.isClosed) try { (server.accept() as javax.net.ssl.SSLSocket).use { runCatching { it.startHandshake() } } } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        val dir = Files.createTempDirectory("svod-pin-").also { Git.init().setDirectory(it.toFile()).call().close() }
        fun messages(t: Throwable) = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        try {
            PinnedHttps.enable(true)
            val on = assertFailsWith<Exception> { SyncGit(dir).use { it.fetchSync("https://localhost:${server.localPort}/r.git", "v") } }
            assertTrue("remote peer address not allowed" in messages(on), messages(on))
            PinnedHttps.enable(false)
            val off = assertFailsWith<Exception> { SyncGit(dir).use { it.fetchSync("https://localhost:${server.localPort}/r.git", "v") } }
            assertFalse("remote peer address not allowed" in messages(off), "off: the self-signed cert fails on its own: ${messages(off)}")
        } finally {
            PinnedHttps.enable(false)
            server.close(); acceptor.join(2000)
        }
    }

    @Test
    fun `scrubText cuts URLs to host and path and masks tokens`() {
        assertEquals(
            "fetch failed: git.example.com/r.git and h.example/p (token ***, Authorization: ***, api_key=***) ***",
            RemoteGuard.scrubText(
                "fetch failed: https://u:pw@git.example.com/r.git?access_token=QTOK#frag and http://h.example/p?x=1 " +
                    "(token abc123def, Authorization: Bearer eyJhbGciOi, api_key=k-123456) ghp_ABCDEFGHIJKLmnop",
            ),
        )
        assertEquals("send Bearer *** now", RemoteGuard.scrubText("send Bearer abcdef123 now"))
    }

    @Test
    fun `with redirects off (denyPrivateRemotes) a real HTTPS fetch does not follow a 302 to another host`() {
        val ks = io.ktor.network.tls.certificates.buildKeyStore {
            certificate("t") { password = "changeit"; domains = listOf("localhost", "127.0.0.1") }
        }
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val target = java.util.concurrent.atomic.AtomicInteger()
        // A minimal HTTPS server: reads the request head, answers [reply], closes.
        fun serve(reply: (Int) -> String, onRequest: () -> Unit = {}): javax.net.ssl.SSLServerSocket {
            val server = ctx.serverSocketFactory.createServerSocket(0, 10, InetAddress.getLoopbackAddress()) as javax.net.ssl.SSLServerSocket
            Thread {
                while (!server.isClosed) try {
                    server.accept().use { sock ->
                        val input = sock.getInputStream().bufferedReader()
                        while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                        onRequest()
                        sock.getOutputStream().apply { write(reply(server.localPort).toByteArray()); flush() }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            return server
        }
        val elsewhere = serve({ "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" }) { target.incrementAndGet() }
        val origin = serve({
            "HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1:${elsewhere.localPort}/r.git/info/refs?service=git-upload-pack\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"
        })
        // The peer check would refuse loopback before any redirect; this test isolates the redirect setting.
        PinnedHttps.enable(false)
        fun repo(): java.nio.file.Path = Files.createTempDirectory("svod-redir-").also { d ->
            Git.init().setDirectory(d.toFile()).call().use { g -> g.repository.config.apply { setBoolean("http", null, "sslVerify", false); save() } }
        }
        try {
            assertFailsWith<Exception> { SyncGit(repo(), followRedirects = false).use { it.fetchSync("https://localhost:${origin.localPort}/r.git", "v") } }
            assertEquals(0, target.get(), "the redirect target must never be contacted")
            // Control: with redirects on (the default mode) the same fetch does reach the other host.
            runCatching { SyncGit(repo()).use { it.fetchSync("https://localhost:${origin.localPort}/r.git", "v") } }
            assertTrue(target.get() > 0, "control: JGit follows the 302 when redirects are allowed")
        } finally {
            origin.close(); elsewhere.close()
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
            assertFalse("https://" in err || "user" in err, "only host/path of the remote is kept: $err")
            assertTrue("git.example.com/r.git" in err, err)
        }
    }

    @Test
    fun `a URL in the failure text is cut to host and path, without query or fragment`() = runBlocking {
        class Echo(root: java.nio.file.Path) : SyncGit(root) {
            override fun fetchSync(remote: String, vaultId: String) = throw RuntimeException("not found: $remote and http://other.example/p?access_token=QTOK#x")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("svod-echo2-")
        SvodEngine.open(dir, scope, dev.svod.engine.security.SecretScanner.OFF, Author("m", "m@svod.local")).use { engine ->
            val sync = SyncEngine(engine, Echo(dir), ConflictStore(), EventBus(), "v", "machine")
            sync.sync("https://git.example.com/org/r.git?token=QSECRET")
            assertEquals("fetch failed: RuntimeException: not found: git.example.com/org/r.git and other.example/p", sync.lastError)
        }
    }
}
