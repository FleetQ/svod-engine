package dev.svod.engine.security

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.VaultPath
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.lifecycle.SvodConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        val NETWORK_CLIENT = Regex(
            """java\.net\.http|HttpURLConnection|io\.ktor\.client|org\.eclipse\.jgit\.transport|java\.net\.Socket\b|SocketChannel|DatagramSocket""",
        )
        /** Maven group ids of error-reporting / telemetry SDKs that phone home when configured. */
        val TELEMETRY_SDK_GROUPS = listOf(
            "io.sentry", "com.bugsnag", "com.rollbar", "io.opentelemetry", "com.newrelic", "com.datadoghq", "io.honeycomb",
        )
        /** An App API route declaration: `post("/api/...")`, `put("/api/...")`, ... */
        val ROUTE = Regex("""\b(?:get|post|put|patch|delete)\("(/api/[^"]+)"""")
        // Built at runtime, like SecretScannerTest: no literal key header is committed.
        private const val DASHES = "-----"
        val PEM_KEY = listOf(
            DASHES + "BEGIN RSA " + "PRIVATE KEY" + DASHES,
            "MIIEowIBAAKCAQEAu1SU1LfVLPHCozMxH2Mo4lgOEePzNm0tRgeLezV6ffAt0gun",
            "VTLw7onLRnrq0/IzW7yWR7QkrmBL7jTKEn5u+qKhbwKfBstIs+bMY2Zkp18gnTxK",
            "LxoS2tFczGkPLPgizskuemMghRniWaoLcyehkd3qqGElvW/VDL5AaWTg0nLVkjRo",
            DASHES + "END RSA " + "PRIVATE KEY" + DASHES,
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
        // ...but only because that user is an admin, which the page must say.
        val noAdmin = shared.copy(users = shared.users.map { it.copy(admin = false) }).validate()
        assertTrue(noAdmin.isNotEmpty(), "validate() no longer requires an admin user off loopback; update $PAGE '## Network exposure'")
        assertBackticked(net, "admin", "## Network exposure")
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
        // `svod-engine init` writes a config with the scan on; the page says so.
        val init = dev.svod.engine.lifecycle.ConfigInit.generate(Paths.get("x"), "none", "sva_t")
        assertEquals(true, init.secretScanning, "svod-engine init no longer enables secretScanning; update $PAGE")
        assertBackticked(scan, "svod-engine init", "## Pre-commit secret scan")
    }

    @Test
    fun personalDataReviewIsDescribedAsOptIn() {
        assertEquals(false, defaults.personalDataScanning, "personalDataScanning default changed; update $PAGE '## Personal data review'")
        val review = section("Personal data review")
        assertBackticked(review, "personalDataScanning", "## Personal data review")
        val lower = review.lowercase()
        assertTrue("off by default" in lower, "$PAGE '## Personal data review' must say it is off by default")
        assertTrue("opt-in" in lower, "$PAGE '## Personal data review' must say it is opt-in")
        assertBackticked(review, "status: provisional", "## Personal data review")
        assertTrue("git history" in lower, "$PAGE '## Personal data review' must say flagged notes still enter git history")
        assertTrue("not a security boundary" in lower, "$PAGE '## Personal data review' must not present the review queue as a security boundary")
        val init = dev.svod.engine.lifecycle.ConfigInit.generate(Paths.get("x"), "none", "sva_t")
        assertEquals(false, init.personalDataScanning, "svod-engine init now enables personalDataScanning; update $PAGE")
    }

    @Test
    fun secretScanExceptionsAreDocumented() = runBlocking {
        // With the scan ON, a note write is blocked but writeBytes commits the same text unscanned.
        val root = Files.createTempDirectory("svod-scan-bypass-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SvodEngine.open(root, scope, SecretScanner(enabled = true)).use { e ->
                val author = Author("a", "a@x")
                val blocked = e.write("notes/key.md", PEM_KEY, null, author)
                assertTrue(blocked is WriteOutcome.Blocked, "an enabled scan should block a note write with a key, got $blocked")
                val raw = e.writeBytes("notes/raw.md", PEM_KEY.toByteArray(), null, author)
                assertTrue(raw is WriteOutcome.Success, "writeBytes now scans ($raw); update $PAGE '## Pre-commit secret scan'")
                assertTrue(e.history("notes/raw.md").isNotEmpty(), "the unscanned write should be in git history")
            }
        } finally {
            scope.cancel()
            root.toFile().deleteRecursively()
        }

        // Every App API route that writes through writeBytes must be named as unscanned.
        val server = Files.readString(engineDir.resolve("src/main/kotlin/dev/svod/engine/api/AppApiServer.kt"))
        val bypassRoutes = Regex("""\.writeBytes\(""").findAll(server).map { call ->
            ROUTE.findAll(server.substring(0, call.range.first)).lastOrNull()?.groupValues?.get(1)
                ?: error("writeBytes call in AppApiServer.kt outside a route")
        }.toSet()
        assertTrue(bypassRoutes.isNotEmpty(), "AppApiServer.kt no longer uses writeBytes; the scan exceptions on $PAGE may be stale")
        val scan = section("Pre-commit secret scan")
        val gaps = section("Known gaps")
        bypassRoutes.forEach { route ->
            assertTrue(route in scan, "$PAGE '## Pre-commit secret scan' does not name the unscanned route $route")
        }
        assertTrue("/api/v1/memory/capture" in bypassRoutes, "the capture route no longer bypasses the scan: $bypassRoutes")
        assertTrue("/api/v1/memory/capture" in gaps, "$PAGE '## Known gaps' must list the capture route's scan exception")
        assertTrue("binary" in scan.lowercase() && "binary" in gaps.lowercase(), "$PAGE must say binary files are not scanned")

        val security = Files.readString(repoRoot.resolve("SECURITY.md"))
        assertTrue("/api/v1/memory/capture" in security, "SECURITY.md must state the transcript-capture scan exception")
        assertTrue(
            !Regex("""preventing\s+accidental\s+disclosure""").containsMatchIn(security),
            "SECURITY.md must not claim the scan prevents disclosure in history without exceptions",
        )
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
        assertTrue("every mcp tool call" !in audit.lowercase(), "$PAGE '## Audit trail' must not claim every MCP call is audited: read-only calls are not")
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
    fun auditChainIsDocumented() {
        val audit = section("Audit trail")
        assertTrue("`svod-engine audit-verify" in audit, "$PAGE '## Audit trail' must name `svod-engine audit-verify`")
        assertTrue("SHA-256" in audit, "$PAGE '## Audit trail' must say the agent audit log is a SHA-256 hash chain")
        assertBackticked(audit, "prevHash", "## Audit trail")
        assertTrue("pre-chain" in audit.lowercase(), "$PAGE '## Audit trail' must say older lines count as pre-chain")
        val gaps = section("Known gaps").lowercase()
        assertTrue("hash chain" in gaps, "$PAGE '## Known gaps' must state the hash chain's limits")
        assertTrue("rewrite" in gaps, "$PAGE '## Known gaps' must say a full rewrite of the audit log is not detected")
        assertTrue("end" in gaps && "cut" in gaps, "$PAGE '## Known gaps' must say lines cut off the end are not detected")
        assertTrue("pre-chain" in gaps, "$PAGE '## Known gaps' must say hash-less lines added at the start pass as pre-chain")
        assertTrue("incomplete last line" in gaps, "$PAGE '## Known gaps' must explain the false alarm while the engine writes")
        assertTrue("byte-for-byte" in audit, "$PAGE '## Audit trail' must say a chained line must match the engine's encoding exactly")
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

    /** [p] under user.home, written the way the page writes it: `~/...`. */
    private fun underHome(p: Path, what: String): String {
        val home = Paths.get(System.getProperty("user.home")).toAbsolutePath()
        assertTrue(p.toAbsolutePath().startsWith(home), "$what ($p) is no longer under user.home; update $PAGE '## Encryption at rest'")
        return "~/" + home.relativize(p.toAbsolutePath()).toString().replace('\\', '/')
    }

    @Test
    fun encryptionAtRestSectionListsEveryDataPath() {
        val enc = section("Encryption at rest")
        val where = "## Encryption at rest"
        val main = engineDir.resolve("src/main/kotlin/dev/svod/engine")

        // Vault locations: the config keys, and every top-level directory inside a vault.
        listOf("vaults[].path", "vaultPath").forEach { assertBackticked(enc, it, where) }
        VaultPath.RESERVED_TOP.forEach { assertBackticked(enc, "$it/", where) }
        // Every named file or directory the engine creates directly under a vault's .svod/: either
        // chained (`x.resolve(".svod").resolve("index")`) or through a variable
        // (`val dir = root.resolve(".svod")` ... `dir.resolve("lock")`). Any other use of
        // `.resolve(".svod")`, and any `".svod/..."` literal or `Paths.get(x, ".svod", ...)` / `Path.of(...)`
        // outside user.home, fails, so a new style of access cannot slip past this list.
        val sources: Map<Path, String> = Files.walk(main).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
            .associateWith { Files.readString(it) }
        val logback = Files.readString(engineDir.resolve("src/main/resources/logback.xml"))
        val chained = Regex("""\.resolve\("\.svod"\)\.resolve\("([^"]+)"\)""")
        val svodVar = Regex("""val (\w+)(?::\s*Path)?\s*=\s*[\w.]+\.resolve\("\.svod"\)[ \t]*\r?\n""")
        val otherSvod = Regex("""(?:Paths\.get|Path\.of)\((?!System\.getProperty\("user\.home"\))[^)]*"\.svod"|"\.svod/""")
        val svodChildren = sources.flatMap { (f, text) ->
            assertTrue(otherSvod.find(text) == null, "$f builds a .svod path in a way this test cannot follow; extend it and update $PAGE '## Encryption at rest'")
            val vars = svodVar.findAll(text).map { it.groupValues[1] }.toList()
            val bare = Regex("""\.resolve\("\.svod"\)""").findAll(text).count() - chained.findAll(text).count()
            assertEquals(vars.size, bare, "$f uses .resolve(\".svod\") in a way this test cannot follow; extend it and update $PAGE '## Encryption at rest'")
            chained.findAll(text).map { it.groupValues[1] }.toList() +
                vars.flatMap { v -> Regex("""\b$v\.resolve\("([^"]+)"\)""").findAll(text).map { it.groupValues[1] }.toList() }
        }.toSet()
        listOf("index", "audit", "lock", "sources.json").forEach {
            assertTrue(it in svodChildren, "found no .svod/$it in the sources: $svodChildren")
        }
        svodChildren.forEach { assertBackticked(enc, ".svod/$it", where) }

        // Environment variables that move a file or directory the engine writes.
        val envNames = (sources.values + logback)
            .flatMap { t -> Regex("""\b(SVOD_\w+_(?:FILE|DIR))\b""").findAll(t).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue("SVOD_DISCOVERY_FILE" in envNames && "SVOD_LOG_DIR" in envNames, "found no path env vars: $envNames")
        envNames.forEach { assertBackticked(enc, "$$it", where) }

        // External sources (ADR-0016) live at any absolute path, and writeBack writes note text there.
        assertEquals(false, dev.svod.engine.sources.ExternalSource(id = "a", path = "/x").writeBack, "ExternalSource.writeBack default changed")
        assertTrue("external source" in enc.lowercase(), "$PAGE '$where' must list external source folders")
        assertBackticked(enc, "writeBack", where)

        // Fixed paths under user.home spelled as literals anywhere in the sources.
        val homeLiteral = Regex("""System\.getProperty\("user\.home"\),\s*((?:"[^"]+"\s*,?\s*)+)\)""")
        val literals = sources.values.flatMap { text ->
            homeLiteral.findAll(text).map { m ->
                "~/" + Regex(""""([^"]+)"""").findAll(m.groupValues[1]).joinToString("/") { it.groupValues[1] }
            }.toList()
        }.toSet()
        assertTrue("~/.svod/secrets" in literals, "SvodNode.kt no longer falls back to ~/.svod/secrets: $literals")
        assertTrue("~/.svod/vaults" in literals, "VaultController.kt no longer creates vaults under ~/.svod/vaults: $literals")
        literals.forEach { assertBackticked(enc, it, where) }

        // Fixed paths under user.home held in constants.
        listOf(
            underHome(dev.svod.engine.lifecycle.EngineDiscovery.DEFAULT_PATH, "EngineDiscovery.DEFAULT_PATH"),
            underHome(dev.svod.engine.lifecycle.HostIdentity.DEFAULT_PATH, "HostIdentity.DEFAULT_PATH"),
            underHome(dev.svod.engine.lifecycle.UpdateService.DEFAULT_LOG, "UpdateService.DEFAULT_LOG"),
            underHome(dev.svod.engine.index.ModelManager.sharedCacheDir(), "ModelManager.sharedCacheDir()"),
        ).forEach { assertBackticked(enc, it, where) }

        // Files the engine writes next to the config file.
        val node = sources.getValue(main.resolve("lifecycle/SvodNode.kt"))
        assertTrue("""parent?.resolve("secrets")""" in node, "SvodNode.kt no longer keeps secrets next to the config; update $PAGE")
        assertBackticked(enc, "secrets/", where)
        val configFiles = Regex("""configDir\.resolve\("([^"]+)"\)""").findAll(node).map { it.groupValues[1] }.toSet()
        assertTrue("audit-api.log" in configFiles, "found no configDir files in SvodNode.kt: $configFiles")
        // Without a config file the config dir is the parent of the ~/.svod/secrets fallback.
        configFiles.forEach { assertBackticked(enc, it, where); assertBackticked(enc, "~/.svod/$it", where) }

        // Logs: the variable and its default come from logback.xml.
        val logDir = assertNotNull(Regex("""\$\{(\w+):-\$\{user\.home\}/([^}]+)\}""").find(logback), "logback.xml log dir pattern changed")
        assertBackticked(enc, "$" + logDir.groupValues[1], where)
        assertBackticked(enc, "~/" + logDir.groupValues[2], where)

        // What the volume does not cover, and who holds the key.
        val lower = enc.lowercase()
        assertTrue("remote" in lower && "sentry" in lower, "$PAGE '$where' must say sync/backup remotes and Sentry are outside the volume")
        assertTrue("key you hold" in lower && "never sees" in lower, "$PAGE '$where' must say the customer holds the key and Svod never sees it")
        listOf("LUKS", "FileVault", "BitLocker").forEach { assertTrue(it in enc, "$PAGE '$where' does not name $it") }
    }

    @Test
    fun encryptionAtRestAdrIsLinked() {
        val adr = "docs/adr/0021-encryption-at-rest.md"
        val adrPath = repoRoot.resolve(adr)
        assertTrue(Files.exists(adrPath), "$adr does not exist")
        val adrText = Files.readString(adrPath)
        assertTrue(Regex("""Status:\s*\*\*Accepted\*\*""").containsMatchIn(adrText), "$adr must be Accepted")
        assertTrue("## Revisit when" in adrText, "$adr must state when to revisit the decision")

        val glance = assertNotNull(page.lines().firstOrNull { it.startsWith("| Is data encrypted at rest?") }, "$PAGE has no 'Is data encrypted at rest?' row")
        assertTrue("(adr/0021-encryption-at-rest.md)" in glance, "$PAGE '## At a glance' encryption row must link ADR-0021")
        assertTrue("(#encryption-at-rest)" in glance, "$PAGE '## At a glance' encryption row must link the Encryption at rest section")
        assertTrue("| No, not by Svod." !in glance, "$PAGE '## At a glance' still gives the old flat answer")
        assertTrue("(adr/0021-encryption-at-rest.md)" in section("Known gaps"), "$PAGE '## Known gaps' must link ADR-0021")

        val readme = Files.readString(repoRoot.resolve("README.md"))
        val deferred = assertNotNull(readme.lines().firstOrNull { it.startsWith("Deferred") }, "README.md has no 'Deferred' line")
        assertTrue("relies on disk encryption" !in deferred, "README.md 'Deferred' line still has the old encryption-at-rest wording")
        assertTrue("($adr)" in deferred, "README.md 'Deferred' line must link $adr")
        assertTrue("($PAGE#encryption-at-rest)" in deferred, "README.md 'Deferred' line must link $PAGE#encryption-at-rest")

        val runbook = Files.readString(repoRoot.resolve("docs/runbook-central-instance.md"))
        assertTrue("(adr/0021-encryption-at-rest.md)" in runbook, "the central-instance runbook must link ADR-0021")
        assertTrue("RequiresMountsFor=" in runbook, "the central-instance runbook must order the engine after the encrypted mount")
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
        // Where note text goes when an endpoint is left unset.
        assertBackticked(out, dev.svod.engine.index.OpenAiEmbedder.DEFAULT_ENDPOINT, "## Outbound connections")
        assertBackticked(out, dev.svod.engine.index.RemoteReranker.DEFAULT_ENDPOINT, "## Outbound connections")
        assertTrue(
            defaults.embedder.ollamaEndpoint.startsWith("http://"),
            "the default Ollama endpoint is no longer plain http; update $PAGE '## Outbound connections'",
        )

        // Connections made by libraries, not by our imports: a logback appender outside logback
        // itself, or a bundled error/telemetry SDK, must be named with its group id.
        val logback = Files.readString(engineDir.resolve("src/main/resources/logback.xml"))
        val foreignAppenders = Regex("""<appender[^>]*class="([^"]+)"""").findAll(logback)
            .map { it.groupValues[1] }.filterNot { it.startsWith("ch.qos.logback.") }.toList()
        val build = Files.readString(engineDir.resolve("build.gradle.kts"))
        val sdkGroups = TELEMETRY_SDK_GROUPS.filter { "\"$it" in build || "\"$it:" in build }
        val groups = (foreignAppenders.map { it.split('.').take(2).joinToString(".") } + sdkGroups).toSet()
        groups.forEach { g ->
            assertTrue(g in out, "$PAGE '## Outbound connections' does not mention the bundled library $g (logback.xml / build.gradle.kts)")
        }
        if (foreignAppenders.isNotEmpty()) {
            assertTrue("(../engine/src/main/resources/logback.xml)" in out, "$PAGE '## Outbound connections' must link logback.xml")
        }
        if ("io.sentry" in groups) {
            assertBackticked(out, "SENTRY_DSN", "## Outbound connections")
            assertBackticked(section("At a glance"), "SENTRY_DSN", "## At a glance")
        }
        assertTrue("| Telemetry? | None" !in page, "$PAGE '## At a glance' must not answer Telemetry with a flat 'None'")

        // Local log files: the directory variable and its default come from logback.xml.
        val logDir = assertNotNull(
            Regex("""\$\{(\w+):-\$\{user\.home\}/([^}]+)\}""").find(logback),
            "logback.xml no longer writes logs under \${VAR:-\${user.home}/...}; update $PAGE '## Data location'",
        )
        val loc = section("Data location")
        assertBackticked(loc, "$" + logDir.groupValues[1], "## Data location")
        assertBackticked(loc, "~/" + logDir.groupValues[2], "## Data location")

        // The blanket claim the page used to make must not come back.
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
        assertTrue(Regex("""off\s+by\s+default""").containsMatchIn(security.lowercase()), "SECURITY.md must say secret scanning is off by default")

        // README links the page, so it must not contradict it either.
        val readme = Files.readString(repoRoot.resolve("README.md"))
        listOf(
            Regex("""App API is \*\*loopback-only\*\*""", RegexOption.IGNORE_CASE),
            Regex("""loopback-only HTTP/JSON API""", RegexOption.IGNORE_CASE),
            Regex("""come in exclusively through MCP""", RegexOption.IGNORE_CASE),
            Regex("""127\.0\.0\.1 only"""),
            Regex("""leaked key never enters history""", RegexOption.IGNORE_CASE),
        ).forEach { r ->
            assertTrue(!r.containsMatchIn(readme), "README.md still says '${r.pattern}', which $PAGE contradicts")
        }
        assertTrue(Regex("""(?i)opt-in pre-commit secret scanning""").containsMatchIn(readme), "README.md must call secret scanning opt-in")
    }
}
