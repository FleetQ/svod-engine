package dev.svod.engine.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import java.net.InetAddress
import java.net.URLDecoder

/**
 * Authentication + authorization for every call under `/api/`, in ONE place so no route can forget it
 * (ADR-0019). Three decisions per call, in order:
 *
 *  1. **Who** — `Authorization: Bearer <key>` resolves through the [UserRegistry]; no header on a
 *     loopback connection with `localAdmin` on is the local UI (admin). Anything else is 401.
 *  2. **Engine admin** — the routes in [ADMIN_ROUTES] (users, agents, backup, vaults, update,
 *     sources, import, index/embedder control) need `admin`; otherwise 403.
 *  3. **Vault access** — every vault-scoped route resolves its `?vault=` (default when omitted): no
 *     read grant ⇒ 403; a non-GET without an EDITOR grant ⇒ 403. An unknown vault id is left to the
 *     route, which answers 404 as before.
 *
 * `/health`, `/ready`, `/metrics` and the reference web viewer stay open: they are the ops surface,
 * not the vault.
 */
object AppApiAuth {

    val PrincipalKey: AttributeKey<Principal> = AttributeKey("svod.principal")
    /** The `userId` an audit line carries when the request was refused before anyone was identified. */
    const val ANONYMOUS = "anonymous"

    private class Rule(val methods: Set<String>?, val path: Regex) {
        fun matches(method: String, p: String) = (methods == null || method in methods) && path.matches(p)
    }

    private val ADMIN_ROUTES = listOf(
        Rule(setOf("POST", "DELETE"), Regex("^/api/v1/vaults(/.*)?$")),
        Rule(null, Regex("^/api/v1/agents(/.*)?$")),
        Rule(null, Regex("^/api/v1/users(/.*)?$")),
        Rule(setOf("POST"), Regex("^/api/v1/secrets$")),
        Rule(setOf("PUT"), Regex("^/api/v1/settings/backup$")),
        Rule(setOf("POST"), Regex("^/api/v1/(backup/now|sync/now|maintenance/reindex|index/(pause|resume|reembed)|graph/rebuild|import|embedder/(test|models))$")),
        Rule(setOf("PUT"), Regex("^/api/v1/embedder$")),
        Rule(null, Regex("^/api/v1/update(/.*)?$")),
        Rule(setOf("POST", "PUT", "PATCH", "DELETE"), Regex("^/api/v1/sources(/.*)?$")),
    )

    /** Routes that are not scoped to one vault (no `?vault=` read check). */
    private val NON_VAULT = Regex("^/api/v1/(vaults|agents|users|me|secrets|update|events)(/.*)?$")

    private val LOOPBACK_NAMES = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
    private val log = org.slf4j.LoggerFactory.getLogger(AppApiAuth::class.java)

    /**
     * The keyless loopback path is only for software on THIS machine talking to `127.0.0.1`. A
     * browser is also on this machine, and a page whose DNS name is re-pointed at 127.0.0.1 (DNS
     * rebinding) is same-origin for it — so the request would arrive here as the local admin. The
     * one thing that page cannot forge is the `Host` header: it carries the attacker's name. A
     * keyless request must therefore name a loopback host; a keyed request is not affected.
     */
    internal fun hostAllowed(call: ApplicationCall): Boolean {
        val raw = call.request.headers[HttpHeaders.Host]?.trim()?.lowercase() ?: return false
        val host = when {
            raw.startsWith("[") -> raw.substringBefore("]").removePrefix("[")     // [::1]:port
            raw.count { it == ':' } > 1 -> raw                                     // bare IPv6
            else -> raw.substringBefore(':')                                       // name:port
        }
        return host in LOOPBACK_NAMES
    }
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /**
     * The path Ktor's router will actually match: empty segments dropped, each segment
     * percent-decoded. Everything below matches against THIS, never the raw request path —
     * `//api/v1/users` and `/api/v1/%75sers` both route to the users handler in Ktor 3.4.3, and
     * a raw-path check would let the first skip authentication and the second skip the admin
     * table. Null when a segment cannot be decoded.
     */
    internal fun canonicalPath(raw: String): String? {
        val segments = raw.split('/').filter { it.isNotEmpty() }.map { seg ->
            runCatching { URLDecoder.decode(seg.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull() ?: return null
        }
        return "/" + segments.joinToString("/")
    }

    fun install(
        app: Application,
        users: UserRegistry?,
        localAdmin: Boolean,
        localPrincipal: Principal,
        /** `?vault=` (null ⇒ default) → the resolved vault id, or null when unknown. */
        resolveVault: (String?) -> String?,
        /** Records when a key was last used; null ⇒ no `lastUsedAt`. */
        activity: UserActivity? = null,
        /** One line per request by a keyed principal; null ⇒ no audit. */
        audit: ApiAuditLog? = null,
    ) {
        if (audit != null) {
            // Monitoring runs first and wraps the whole call: after proceed() the response status
            // is final and the principal (set below, in Plugins) is on the call.
            app.intercept(ApplicationCallPipeline.Monitoring) {
                val raw = call.request.path()
                if (!raw.startsWith("/api/") && !raw.startsWith("//")) return@intercept
                var failed = false
                try {
                    proceed()
                } catch (e: Throwable) {
                    failed = true   // the request that produced a 500 is the one an admin will look for
                    throw e
                } finally {
                    val p = call.attributes.getOrNull(PrincipalKey)
                    val status = call.response.status()?.value ?: (if (failed) 500 else 0)
                    // A refused request (401/403 before any principal) is audited as "anonymous":
                    // key guessing against a shared engine belongs in the file an admin reads after
                    // an incident, not only in the engine's own log.
                    val who = when {
                        p == null && status in setOf(400, 401, 403) -> ANONYMOUS
                        p != null && !p.local -> p.userId
                        else -> null
                    }
                    if (who != null) {
                        audit.record(
                            userId = who,
                            method = call.request.httpMethod.value,
                            path = canonicalPath(raw) ?: raw,
                            vault = call.request.queryParameters["vault"],
                            status = status,
                            ip = runCatching { call.request.origin.remoteAddress }.getOrNull(),
                        )
                    }
                }
            }
        }
        app.intercept(ApplicationCallPipeline.Plugins) {
            val raw = call.request.path()
            val path = canonicalPath(raw)
            if (path == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "path cannot be decoded"))
                return@intercept finish()
            }
            if (!path.startsWith("/api/")) return@intercept
            if (path != raw) {
                // Canonical differs from what was sent: `//`, `%xx` or a trailing slash. Every API
                // route is fixed ASCII segments plus `[a-z0-9_-]` ids, so nothing legitimate is lost.
                call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "API paths must be canonical (no empty or encoded segments)"))
                return@intercept finish()
            }

            val principal = authenticate(call, users, localAdmin, localPrincipal, activity)
            if (principal == null) {
                val reason = when {
                    call.request.headers[HttpHeaders.Authorization] != null -> "key not accepted"
                    localAdmin && isLoopback(call) -> "keyless loopback request with non-loopback Host '${call.request.headers[HttpHeaders.Host]}'"
                    else -> "no key"
                }
                denied(call, path, reason)
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized", "a personal API key is required (Authorization: Bearer <key>)"))
                return@intercept finish()
            }
            call.attributes.put(PrincipalKey, principal)

            val method = call.request.httpMethod.value
            if (!principal.admin && ADMIN_ROUTES.any { it.matches(method, path) }) {
                denied(call, path, "${principal.userId} is not an admin")
                call.respond(HttpStatusCode.Forbidden, ErrorDto("forbidden", "this operation requires an engine admin"))
                return@intercept finish()
            }

            if (NON_VAULT.matches(path)) return@intercept
            // Federated search decides per vault inside the route (a user may lack the default vault).
            if (path == "/api/v1/search" && call.request.queryParameters["across"].equals("true", ignoreCase = true)) return@intercept

            val vaultId = resolveVault(call.request.queryParameters["vault"]) ?: return@intercept
            if (!principal.canRead(vaultId)) {
                // Indistinguishable from a vault that does not exist: a grant-less caller learns
                // nothing about which vault ids the engine holds.
                denied(call, path, "${principal.userId} has no grant on vault '$vaultId'")
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "vault"))   // byte-identical to the route's own 404
                return@intercept finish()
            }
            if (method != "GET" && !principal.canWrite(vaultId)) {
                denied(call, path, "${principal.userId} is a reader of vault '$vaultId'")
                call.respond(HttpStatusCode.Forbidden, ErrorDto("forbidden", "vault '$vaultId' is read-only for ${principal.userId}"))
                return@intercept finish()
            }
        }
    }

    /** The value of the key is never logged — only that a request from [ip] was refused, and why. */
    private fun denied(call: ApplicationCall, path: String, reason: String) {
        log.warn("auth refused: {} {} from {}: {}", call.request.httpMethod.value, path,
            runCatching { call.request.origin.remoteAddress }.getOrNull() ?: "?", reason)
    }

    internal fun authenticate(
        call: ApplicationCall, users: UserRegistry?, localAdmin: Boolean, localPrincipal: Principal,
        activity: UserActivity? = null,
    ): Principal? {
        val header = call.request.headers[HttpHeaders.Authorization]
        if (header != null) {
            val key = header.trim().let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else it }
            return users?.authenticate(key)?.also { activity?.touch(it.userId) }
        }
        return if (localAdmin && isLoopback(call) && hostAllowed(call)) localPrincipal else null
    }

    /**
     * Only the socket's peer ADDRESS decides, and only when it is an IP literal — `remoteHost`
     * is a reverse-DNS name on both Netty and CIO, and `InetAddress.getByName` on a name is a
     * forward lookup: a blocking resolver call on every keyless request, and a check an attacker
     * with a PTR record and a 127.0.0.1 A record can pass. A literal never touches DNS.
     */
    private fun isLoopback(call: ApplicationCall): Boolean {
        val addr = runCatching { call.request.origin.remoteAddress }.getOrNull() ?: return false
        if (addr in LOOPBACK_NAMES) return true
        if (!IPV4.matches(addr) && ':' !in addr) return false
        return runCatching { InetAddress.getByName(addr).isLoopbackAddress }.getOrDefault(false)
    }
}
