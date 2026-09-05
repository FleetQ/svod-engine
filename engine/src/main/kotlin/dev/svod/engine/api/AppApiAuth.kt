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

    fun install(
        app: Application,
        users: UserRegistry?,
        localAdmin: Boolean,
        localPrincipal: Principal,
        /** `?vault=` (null ⇒ default) → the resolved vault id, or null when unknown. */
        resolveVault: (String?) -> String?,
    ) {
        app.intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (!path.startsWith("/api/")) return@intercept

            val principal = authenticate(call, users, localAdmin, localPrincipal)
            if (principal == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized", "a personal API key is required (Authorization: Bearer <key>)"))
                return@intercept finish()
            }
            call.attributes.put(PrincipalKey, principal)

            val method = call.request.httpMethod.value
            if (!principal.admin && ADMIN_ROUTES.any { it.matches(method, path) }) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("forbidden", "this operation requires an engine admin"))
                return@intercept finish()
            }

            if (NON_VAULT.matches(path)) return@intercept
            // Federated search decides per vault inside the route (a user may lack the default vault).
            if (path == "/api/v1/search" && call.request.queryParameters["across"].equals("true", ignoreCase = true)) return@intercept

            val vaultId = resolveVault(call.request.queryParameters["vault"]) ?: return@intercept
            if (!principal.canRead(vaultId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("forbidden", "no access to vault '$vaultId'"))
                return@intercept finish()
            }
            if (method != "GET" && !principal.canWrite(vaultId)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("forbidden", "vault '$vaultId' is read-only for ${principal.userId}"))
                return@intercept finish()
            }
        }
    }

    private fun authenticate(call: ApplicationCall, users: UserRegistry?, localAdmin: Boolean, localPrincipal: Principal): Principal? {
        val header = call.request.headers[HttpHeaders.Authorization]
        if (header != null) {
            val key = header.trim().let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else it }
            return users?.authenticate(key)
        }
        return if (localAdmin && isLoopback(call)) localPrincipal else null
    }

    private fun isLoopback(call: ApplicationCall): Boolean {
        val origin = call.request.origin
        val candidates = listOfNotNull(runCatching { origin.remoteAddress }.getOrNull(), runCatching { origin.remoteHost }.getOrNull())
        return candidates.any { addr ->
            addr in LOOPBACK_NAMES || runCatching { InetAddress.getByName(addr).isLoopbackAddress }.getOrDefault(false)
        }
    }
}
