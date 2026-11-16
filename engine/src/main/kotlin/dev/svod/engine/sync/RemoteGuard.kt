package dev.svod.engine.sync

import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.JDKHttpConnection
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory
import org.eclipse.jgit.transport.HttpTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * SSRF guard for sync and backup remotes (`denyPrivateRemotes`). A hosted engine lets people name a git
 * remote; without this it would fetch from and push to the host's own network. When [deny] is on, a
 * remote is refused unless it is an `https` URL whose host is a DNS name that resolves ONLY to public
 * addresses. [check] runs before every fetch/push (the answer can change between cycles), after `Secrets.resolve`.
 *
 * Parsing: the remote is read with JGit's [URIish] (what the transport uses) and must give the same scheme
 * and host to [URI] and [URL] (what the JDK connection uses); anything ambiguous is refused: a backslash,
 * whitespace, a fragment, userinfo holding `@ ? # / \`, an IP literal in any spelling (dotted, decimal,
 * octal, hex, bracketed IPv6), a trailing-dot host. The last label of the host must start with a letter.
 *
 * Addresses refused: loopback, private (RFC1918), link-local (169.254/16, fe80::/10), unique-local
 * (fc00::/7), CGNAT (100.64/10), multicast, unspecified, and IPv6 transition prefixes that can carry one
 * (NAT64 64:ff9b::/96 and 64:ff9b:1::/48, 6to4 2002::/16, Teredo 2001::/32). IPv4-mapped `::ffff:a.b.c.d`
 * is judged as the IPv4 address it carries. That covers the cloud metadata endpoints.
 *
 * DNS rebinding between [check] and the transport's own lookup is closed by [PinnedHttps]: with the guard
 * on, every JGit HTTPS connection verifies the address it actually connected to, inside the TLS handshake
 * and before any request is sent. Plain http has no such hook, which is why the guard demands https.
 *
 * The messages never contain the remote, so no credentials can leak through them. [resolver] is
 * injectable for tests.
 */
class RemoteGuard(
    val deny: Boolean,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    class Refused(message: String) : Exception(message)

    /** Throws [Refused] when [remote] must not be contacted. Does nothing when the guard is off. */
    fun check(remote: String) {
        if (!deny) return
        val host = hostOf(remote.trim())
        val addrs = try { resolver(host) } catch (_: Exception) { throw Refused("remote host could not be resolved") }
        if (addrs.isEmpty() || addrs.any(::blocked)) throw Refused("remote host not allowed")
    }

    companion object {
        val OFF = RemoteGuard(false)
        private val LETTER_LABEL = Regex("""^[a-z][a-z0-9-]*$""")
        private val DNS_NAME = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$""")

        /** The host [remote] names, after every parser agrees on it; throws [Refused] otherwise. */
        internal fun hostOf(remote: String): String {
            if (remote.any { it == '\\' || it.isWhitespace() || it.isISOControl() }) throw Refused("remote not allowed: not a plain URL")
            val ish = try { URIish(remote) } catch (_: Exception) { throw Refused("remote not allowed: not a valid URL") }
            if (!"https".equals(ish.scheme, ignoreCase = true)) throw Refused("remote not allowed: only https remotes are accepted")
            for (part in listOfNotNull(ish.user, ish.pass)) {
                if (part.any { it in "@?#/\\" }) throw Refused("remote not allowed: ambiguous credentials in the URL")
            }
            val host = ish.host?.lowercase() ?: throw Refused("remote not allowed: no host")
            val uri = try { URI(remote) } catch (_: Exception) { throw Refused("remote not allowed: not a valid URL") }
            val url = try { URL(remote) } catch (_: Exception) { throw Refused("remote not allowed: not a valid URL") }
            if (uri.rawFragment != null) throw Refused("remote not allowed: a URL fragment is not accepted")
            if (!"https".equals(uri.scheme, ignoreCase = true) || !"https".equals(url.protocol, ignoreCase = true) ||
                !host.equals(uri.host, ignoreCase = true) || !host.equals(url.host, ignoreCase = true)
            ) throw Refused("remote not allowed: the URL is ambiguous")
            if (host.startsWith("[") || ':' in host) throw Refused("remote host not allowed: IP addresses are refused")
            if (!DNS_NAME.matches(host) || !LETTER_LABEL.matches(host.substringAfterLast('.'))) {
                throw Refused("remote host not allowed: a DNS name is required")
            }
            return host
        }

        internal fun blocked(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
            val b = a.address
            return when (a) {
                is Inet4Address -> {
                    val b0 = b[0].toInt() and 0xff; val b1 = b[1].toInt() and 0xff
                    b0 == 0 || (b0 == 100 && b1 in 64..127)                       // 0.0.0.0/8, CGNAT 100.64.0.0/10
                }
                is Inet6Address -> {
                    fun u(i: Int) = b[i].toInt() and 0xff
                    (u(0) and 0xfe) == 0xfc ||                                     // unique-local fc00::/7
                        (u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xff && u(3) == 0x9b &&
                            (b.slice(4..11).all { it == 0.toByte() } || (u(4) == 0 && u(5) == 1))) || // NAT64 64:ff9b::/96, 64:ff9b:1::/48
                        (u(0) == 0x20 && u(1) == 0x02) ||                          // 6to4 2002::/16
                        (u(0) == 0x20 && u(1) == 0x01 && u(2) == 0 && u(3) == 0)   // Teredo 2001::/32
                }
                else -> true
            }
        }
    }
}

/**
 * Connect-time half of [RemoteGuard]: JGit's HTTP connection factory, wrapped so that every HTTPS
 * connection checks the peer it actually reached. The check sits in the TLS trust manager, which the JDK
 * calls with the connected socket during the handshake, so no request (and no credential) is sent to a
 * refused address. Certificate and hostname checks are delegated unchanged, so SNI and host verification
 * stay on the name in the URL. Process-wide (JGit's factory is static); [enable] follows the engine config.
 * A forward proxy would be the peer and is refused when it sits on a private address.
 */
object PinnedHttps {
    @Volatile
    var enabled: Boolean = false
        private set

    private val base = JDKHttpConnectionFactory()

    private val factory = object : HttpConnectionFactory {
        override fun create(url: URL): HttpConnection = create(url, Proxy.NO_PROXY)
        override fun create(url: URL, proxy: Proxy): HttpConnection =
            if (!enabled || !"https".equals(url.protocol, ignoreCase = true)) base.create(url, proxy) else Checked(url, proxy)
    }

    /** Turns the peer check on or off for every JGit HTTP transport in this process. */
    @Synchronized
    fun enable(on: Boolean) {
        enabled = on
        if (on && HttpTransport.getConnectionFactory() !== factory) HttpTransport.setConnectionFactory(factory)
    }

    private class Checked(url: URL, proxy: Proxy) : JDKHttpConnection(url, proxy) {
        init { configure(null, null, null) }

        // JGit calls this itself for `http.sslVerify=false`; the peer check stays in front of whatever it passes.
        override fun configure(km: Array<KeyManager>?, tm: Array<TrustManager>?, random: SecureRandom?) {
            val inner = tm?.filterIsInstance<X509TrustManager>()?.firstOrNull() ?: defaultTrustManager()
            super.configure(km, arrayOf(PeerCheck(inner)), random)
        }
    }

    private fun defaultTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()

    /** Refuses the handshake when the connected peer is a blocked address; otherwise defers to [inner]. */
    internal class PeerCheck(private val inner: X509TrustManager) : X509ExtendedTrustManager() {
        private fun peer(socket: Socket?) {
            val a = socket?.inetAddress ?: throw CertificateException("remote peer address unknown")
            if (RemoteGuard.blocked(a)) throw CertificateException("remote peer address not allowed")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
            peer(socket)
            if (inner is X509ExtendedTrustManager) inner.checkServerTrusted(chain, authType, socket) else inner.checkServerTrusted(chain, authType)
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            throw CertificateException("remote peer address unknown")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("remote peer address unknown")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = throw CertificateException("not a server")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = throw CertificateException("not a server")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("not a server")
        override fun getAcceptedIssuers(): Array<X509Certificate> = inner.acceptedIssuers
    }
}
