package dev.svod.engine.security

import dev.svod.engine.core.VaultPath
import dev.svod.engine.lifecycle.SvodConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps docs/security-overview.md honest: the defaults, validation rules, roles, secret ref
 * prefixes and audit paths it states are read from the code, so the page fails CI when it drifts.
 */
class SecurityOverviewDocsTest {

    private val engineDir: Path = Paths.get(System.getProperty("svod.projectDir") ?: System.getProperty("user.dir"))
    private val repoRoot: Path = engineDir.toAbsolutePath().parent
    private val pagePath: Path = repoRoot.resolve(PAGE)

    private val page: String by lazy {
        assertTrue(Files.exists(pagePath), "$PAGE does not exist")
        Files.readString(pagePath)
    }

    private val defaults = SvodConfig(vaultPath = "x")

    /** The page text from the `## <heading>` line up to the next `## ` heading. */
    private fun section(heading: String): String {
        val lines = page.lines()
        val start = lines.indexOfFirst { it.startsWith("## ") && it.removePrefix("## ").trim().startsWith(heading) }
        assertTrue(start >= 0, "$PAGE has no '## $heading' section")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun assertBackticked(text: String, value: String, where: String) =
        assertTrue("`$value`" in text, "$PAGE '$where' does not mention `$value`")

    private companion object {
        const val PAGE = "docs/security-overview.md"
        /** Imports that mean a file can open a connection to another host. */
        val NETWORK_CLIENT = Regex("""java\.net\.http|HttpURLConnection|io\.ktor\.client|org\.eclipse\.jgit\.transport""")
        val PEM_KEY = listOf(
            "-----BEGIN RSA PRIVATE KEY-----",
            "MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun",
            "VTLw7onLRnrq0/IzW7yWR7QkrmBL7jTKEn5u+qKhbwKfBstIs+bMY2Zkp18gnTxK",
            "LxoS2tFczGkPLPgizskuemMghRniWaoLcyehkd3qqGElvW/VDL5AaWTg0nLVkjRo",
            "-----END RSA PRIVATE KEY-----",
        ).joinToString("\n")
    }

    @Test
    fun pageExistsAndIsLinked() {
        assertTrue(Files.exists(pagePath), "$PAGE does not exist")
        val readme = Files.readString(repoRoot.resolve("README.md"))
        assertTrue("]($PAGE)" in readme, "README.md does not link $PAGE")
        val security = Files.readString(repoRoot.resolve("SECURITY.md"))
        assertTrue("]($PAGE)" in security, "SECURITY.md does not link $PAGE")
    }

    @Test
    fun networkDefaultsMatchConfig() {
        val net = section("Network exposure")
        assertBackticked(net, defaults.host, "## Network exposure")
        assertBackticked(net, defaults.appApiPort.toString(), "## Network exposure")
        assertBackticked(net, defaults.mcpPort.toString(), "## Network exposure")

        // What validate() demands before the engine may leave loopback must be on the page.
        val errors = SvodConfig(vaultPath = "x", host = "0.0.0.0").validate()
        val required = listOf("appApiTls", "mcpTls", "users", "localAdmin")
        required.forEach { field ->
            assertTrue(errors.any { field in it }, "SvodConfig.validate() no longer mentions $field off loopback: $errors")
            assertBackticked(net, field, "## Network exposure")
        }
        // A fully configured shared engine passes, so the page's list is complete.
        val tls = SvodConfig.TlsSettings(keystorePath = "k", keystorePassword = "env:P", keyAlias = "a", keyPassword = "env:P")
        val shared = SvodConfig(
            vaultPath = "x",
            host = "0.0.0.0",
            appApiTls = tls,
            mcpTls = tls,
            localAdmin = false,
            users = listOf(SvodConfig.UserSettings(userId = "admin", name = "Admin", keyRef = "env:K", admin = true)),
        )
        assertEquals(emptyList(), shared.validate(), "a TLS + users + localAdmin=false shared engine should be valid")
    }

    @Test
    fun secretScanIsDescribedAsOptIn() {
        assertEquals(false, defaults.secretScanning, "secretScanning default changed; update $PAGE '## Pre-commit secret scan'")
        assertTrue(SecretScanner(defaults.secretScanning).scan(PEM_KEY).isEmpty(), "the default scanner should find nothing")
        assertTrue(SecretScanner(true).scan(PEM_KEY).isNotEmpty(), "the enabled scanner should find a full PEM key")
        val scan = section("Pre-commit secret scan")
        assertBackticked(scan, "secretScanning", "## Pre-commit secret scan")
        assertTrue("off by default" in scan.lowercase(), "$PAGE '## Pre-commit secret scan' must say it is off by default")
        assertTrue("opt-in" in scan.lowercase(), "$PAGE '## Pre-commit secret scan' must say it is opt-in")
    }

    @Test
    fun authClaimsMatchCode() {
        SvodConfig.SECRET_REF_PREFIXES.forEach { assertBackticked(page, it, "page") }
        SvodConfig.USER_ROLES.forEach { assertBackticked(page, it, "page") }
        val auth = section("Authentication and access control")
        assertBackticked(auth, "localAdmin", "## Authentication and access control")
        assertTrue(
            Regex("""`localAdmin`[^\n]*default[^\n]*`${defaults.localAdmin}`""").containsMatchIn(auth),
            "$PAGE '## Authentication and access control' must state `localAdmin` defaults to `${defaults.localAdmin}`",
        )
        // An inline key is rejected, as the page says.
        val inline = SvodConfig(
            vaultPath = "x",
            users = listOf(SvodConfig.UserSettings(userId = "a", name = "A", keyRef = "svk_plainkey", admin = true)),
        ).validate()
        assertTrue(inline.any { "keyRef" in it }, "validate() no longer rejects an inline personal key: $inline")
        assertBackticked(auth, "svk_", "## Authentication and access control")
    }

    @Test
    fun auditPathsMatchSource() {
        val audit = section("Audit trail")
        assertBackticked(audit, ".svod/audit/audit.log", "## Audit trail")
        assertBackticked(audit, "audit-api.log", "## Audit trail")
        val node = Files.readString(engineDir.resolve("src/main/kotlin/dev/svod/engine/lifecycle/SvodNode.kt"))
        assertTrue(
            """resolve(".svod").resolve("audit").resolve("audit.log")""" in node,
            "SvodNode.kt no longer writes the agent audit log to .svod/audit/audit.log; update $PAGE '## Audit trail'",
        )
        assertTrue(
            """configDir.resolve("audit-api.log")""" in node,
            "SvodNode.kt no longer writes the App API audit log to audit-api.log in the config dir; update $PAGE '## Audit trail'",
        )
    }

    @Test
    fun deletionAndGapsAreStated() {
        assertTrue(".trash" in VaultPath.RESERVED_TOP, "VaultPath.RESERVED_TOP no longer has .trash; update $PAGE")
        val del = section("Deletion and retention").lowercase()
        assertTrue("`.trash/`" in del, "$PAGE '## Deletion and retention' must mention `.trash/`")
        assertTrue("purge" in del, "$PAGE '## Deletion and retention' must mention purge")
        assertTrue("git history" in del, "$PAGE '## Deletion and retention' must say purged content stays in git history")
        val gaps = section("Known gaps").lowercase()
        listOf("encryption at rest", "sso", "soc 2", "audit log", "expiry").forEach {
            assertTrue(it in gaps, "$PAGE '## Known gaps' does not mention '$it'")
        }
    }

    @Test
    fun outboundConnectionsListEveryNetworkClient() {
        val out = section("Outbound connections")
        // Every main source file that opens an HTTP connection or a git transport must be linked
        // there, so a new client can't add a data flow without the page noticing.
        val srcRoot = engineDir.resolve("src/main/kotlin/dev/svod/engine")
        val clients = Files.walk(srcRoot).use { s ->
            s.filter { it.toString().endsWith(".kt") }
                .filter { NETWORK_CLIENT.containsMatchIn(Files.readString(it)) }
                .map { srcRoot.relativize(it).toString().replace('\\', '/') }
                .toList()
        }
        assertTrue(clients.isNotEmpty(), "found no network client in $srcRoot")
        val missing = clients.filterNot { "(../engine/src/main/kotlin/dev/svod/engine/$it)" in out }
        assertTrue(missing.isEmpty(), "$PAGE '## Outbound connections' does not link these network clients: $missing")

        // Every provider that sends text to an endpoint is named, with its endpoint key.
        val local = setOf("none", "onnx-local", "local-onnx")
        (SvodConfig.PROVIDERS + SvodConfig.RERANKER_PROVIDERS + SvodConfig.EXTRACTION_PROVIDERS)
            .filter { it !in local }
            .forEach { assertBackticked(out, it, "## Outbound connections") }
        listOf(
            "embedder.endpoint", "embedder.ollamaEndpoint", "reranker.endpoint",
            "graph.summaryEndpoint", "memory.extractionEndpoint", "denyPrivateRemotes",
        ).forEach { assertBackticked(out, it, "## Outbound connections") }
        assertBackticked(out, defaults.embedder.ollamaEndpoint, "## Outbound connections")
        assertTrue(
            defaults.embedder.ollamaEndpoint.startsWith("http://"),
            "the default Ollama endpoint is no longer plain http; update $PAGE '## Outbound connections'",
        )

        // The blanket claim the page used to make must not come back.
        val loc = section("Data location")
        assertTrue(
            !Regex("""only if the operator configures a `remote-openai`""").containsMatchIn(loc),
            "$PAGE '## Data location' must not limit outbound note text to remote-openai",
        )
    }

    @Test
    fun securityMdNoLongerOverclaims() {
        val security = Files.readString(repoRoot.resolve("SECURITY.md"))
        assertTrue(
            !Regex("""127\.0\.0\.1`?\s+exclusively|exclusively\s+`?127\.0\.0\.1""").containsMatchIn(security),
            "SECURITY.md still says the App API binds to 127.0.0.1 exclusively",
        )
        assertTrue("TLS" in security && "shared engine" in security, "SECURITY.md must explain TLS for a shared engine")
        assertTrue("`secretScanning`" in security, "SECURITY.md must name the `secretScanning` opt-in")
        assertTrue(Regex("""off\s+by\s+default""").containsMatchIn(security.lowercase()),"SECURITY.md must say secret scanning is off by default")
    }
}
