package dev.svod.engine.sync

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * SSRF guard for sync and backup remotes (`denyPrivateRemotes`). A hosted engine lets people name a git
 * remote; without this it would fetch from and push to the host's own network. When [deny] is on, a
 * remote is refused unless it is an `http(s)` URL whose host is a DNS name that resolves ONLY to
 * public addresses. [check] runs before every fetch/push (the answer can change between cycles — DNS
 * rebinding), after `Secrets.resolve`.
 *
 * Refused: any scheme but http/https (file:, ssh, git, scp-style `git@host:path`, plain paths), any IP
 * literal, and a name that resolves to a loopback, private (RFC1918), link-local (169.254/16,
 * fe80::/10), unique-local (fc00::/7), CGNAT (100.64/10), multicast, or unspecified address, or an IPv6
 * transition address that can embed one (NAT64 64:ff9b::/96 and 64:ff9b:1::/48, 6to4 2002::/16, Teredo 2001::/32).
 * IPv4-mapped `::ffff:a.b.c.d` is judged as the IPv4 address it carries. The messages
 * never contain the remote, so no credentials can leak through them.
 *
 * [resolver] is injectable for tests.
 */
class RemoteGuard(
    val deny: Boolean,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    class Refused(message: String) : Exception(message)

    /** Throws [Refused] when [remote] must not be contacted. Does nothing when the guard is off. */
    fun check(remote: String) {
        if (!deny) return
        val m = HTTP.find(remote.trim()) ?: throw Refused("remote not allowed: only http(s) remotes are accepted")
        val host = m.groupValues[1]
        if (host.startsWith("[") || IPV4_LITERAL.matches(host)) throw Refused("remote host not allowed: IP addresses are refused")
        val addrs = try { resolver(host) } catch (_: Exception) { throw Refused("remote host could not be resolved") }
        if (addrs.isEmpty() || addrs.any(::blocked)) throw Refused("remote host not allowed")
    }

    companion object {
        val OFF = RemoteGuard(false)
        private val HTTP = Regex("""^https?://(?:[^/@?#]*@)?(\[[^\]]*\]|[^/:?#]+)""", RegexOption.IGNORE_CASE)
        private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

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
