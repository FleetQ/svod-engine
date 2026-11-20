package dev.svod.engine.lifecycle

import dev.svod.engine.index.ModelManager
import dev.svod.engine.lifecycle.Doctor.Status
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoctorTest {

    @TempDir
    lateinit var tmp: Path

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun env(
        env: Map<String, String> = emptyMap(),
        modelCache: Path = tmp.resolve("model-cache"),
    ) = Doctor.Env(
        env = env,
        discoveryFile = tmp.resolve("discovery").resolve("engine.json"),
        modelCacheDir = modelCache,
    )

    private fun validConfig(): SvodConfig {
        val vault = Files.createDirectories(tmp.resolve("vault"))
        Files.createDirectories(vault.resolve(".git"))
        val app = freePort()
        var mcp = freePort()
        while (mcp == app) mcp = freePort()
        return SvodConfig(
            vaultPath = vault.toString(),
            appApiPort = app,
            mcpPort = mcp,
            embedder = SvodConfig.EmbedderSettings(provider = "none"),
            agents = listOf(SvodConfig.AgentSettings(token = "literal-token-value", agentId = "friday", role = "WRITE")),
        )
    }

    private fun List<Doctor.Check>.fails() = filter { it.status == Status.FAIL }
    private fun List<Doctor.Check>.warns() = filter { it.status == Status.WARN }
    private fun List<Doctor.Check>.text() = Doctor.format(this).joinToString("\n")

    @Test
    fun `a valid config has no failures and exits 0`() {
        val checks = Doctor.run(validConfig(), env())
        assertTrue(checks.fails().isEmpty(), checks.text())
        assertEquals(0, Doctor.exitCode(checks))
        assertFalse("literal-token-value" in checks.text(), checks.text())
        assertTrue(Doctor.format(checks).last().matches(Regex("""\d+ ok, \d+ warn, 0 fail""")), checks.text())
    }

    @Test
    fun `an unset env token fails naming the agent and the variable, never a value`() {
        val config = validConfig().let { c -> c.copy(agents = listOf(c.agents.single().copy(token = "env:SVOD_MISSING_TOKEN"))) }
        val checks = Doctor.run(config, env(env = mapOf("OTHER" to "other-secret-value")))
        val fail = checks.fails().single()
        assertTrue("friday" in fail.message && "SVOD_MISSING_TOKEN" in fail.message, fail.toString())
        val fix = fail.fix ?: ""
        assertTrue("set" in fix && "SVOD_MISSING_TOKEN" in fix, fail.toString())
        assertEquals(1, Doctor.exitCode(checks))
        assertFalse("other-secret-value" in checks.text(), checks.text())
    }

    @Test
    fun `a resolved env token is ok and its value is never printed`() {
        val config = validConfig().let { c -> c.copy(agents = listOf(c.agents.single().copy(token = "env:SVOD_TOKEN"))) }
        val checks = Doctor.run(config, env(env = mapOf("SVOD_TOKEN" to "resolved-secret-value")))
        assertTrue(checks.fails().isEmpty(), checks.text())
        assertFalse("resolved-secret-value" in checks.text(), checks.text())
    }

    @Test
    fun `a user keyRef to a missing file fails naming the user`() {
        val config = validConfig().copy(
            users = listOf(SvodConfig.UserSettings(userId = "ana", name = "Ana", keyRef = "file:/nonexistent/key")),
        )
        val checks = Doctor.run(config, env())
        val fail = checks.fails().single()
        assertTrue("ana" in fail.message && "file:/nonexistent/key" in fail.message, fail.toString())
        assertEquals(1, Doctor.exitCode(checks))
    }

    @Test
    fun `a missing TLS keystore fails`() {
        val config = validConfig().copy(
            mcpTls = SvodConfig.TlsSettings(tmp.resolve("nope.p12").toString(), "file:${tmp.resolve("nope.pw")}", "svod", "env:SVOD_KP"),
        )
        val checks = Doctor.run(config, env(env = mapOf("SVOD_KP" to "x")))
        val fails = checks.fails()
        assertTrue(fails.any { "mcpTls.keystorePath" in it.message }, checks.text())
        assertTrue(fails.any { "mcpTls.keystorePassword" in it.message }, checks.text())
        assertFalse(fails.any { "mcpTls.keyPassword" in it.message }, checks.text())
    }

    @Test
    fun `a busy port fails, and warns when a running engine holds it`() {
        val config = validConfig()
        ServerSocket().use { held ->
            held.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            val port = held.localPort
            val busy = config.copy(appApiPort = port)
            val checks = Doctor.run(busy, env())
            val fail = checks.fails().single()
            assertTrue("$port" in fail.message && "appApiPort" in fail.message, fail.toString())
            assertEquals(1, Doctor.exitCode(checks))

            val e = env()
            Files.createDirectories(e.discoveryFile.parent)
            Files.writeString(
                e.discoveryFile,
                """{"host":"127.0.0.1","appApiPort":$port,"mcpPort":${config.mcpPort},"pid":${ProcessHandle.current().pid()}}""",
            )
            val withEngine = Doctor.run(busy, e)
            assertTrue(withEngine.fails().isEmpty(), withEngine.text())
            assertTrue(withEngine.warns().any { "already running" in it.message }, withEngine.text())
            assertTrue(withEngine.warns().any { "$port" in it.message && "appApiPort" in it.message }, withEngine.text())
            assertEquals(0, Doctor.exitCode(withEngine))
        }
    }

    @Test
    fun `a dead pid in the discovery file is ignored`() {
        val e = env().copy(pidAlive = { false })
        Files.createDirectories(e.discoveryFile.parent)
        Files.writeString(e.discoveryFile, """{"appApiPort":1,"mcpPort":2,"pid":42}""")
        val checks = Doctor.run(validConfig(), e)
        assertFalse(checks.any { "already running" in it.message }, checks.text())
    }

    @Test
    fun `a non-writable vault fails, a missing one warns, a non-repo warns`() {
        val config = validConfig()
        val vault = config.vault()

        Files.delete(vault.resolve(".git"))
        val noRepo = Doctor.run(config, env())
        assertTrue(noRepo.fails().isEmpty(), noRepo.text())
        assertTrue(noRepo.warns().any { "git repo" in it.message && "'default'" in it.name }, noRepo.text())

        val missing = config.copy(vaultPath = tmp.resolve("parent").resolve("not-yet").toString())
        Files.createDirectories(tmp.resolve("parent"))
        val m = Doctor.run(missing, env())
        assertTrue(m.fails().isEmpty(), m.text())
        assertTrue(m.warns().any { "will be created on first start" in it.message }, m.text())

        val readOnly = Files.createDirectories(tmp.resolve("ro"))
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeTrue(!Files.isWritable(readOnly), "running as a user that can write anywhere")
            val ro = Doctor.run(config.copy(vaultPath = readOnly.toString()), env())
            val fail = ro.fails().single()
            assertTrue("'default'" in fail.name && readOnly.toString() in fail.message, fail.toString())
            assertEquals(1, Doctor.exitCode(ro))
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `no agents warns without failing`() {
        val checks = Doctor.run(validConfig().copy(agents = emptyList()), env())
        assertTrue(checks.warns().any { "no agents configured" in it.message }, checks.text())
        assertEquals(0, Doctor.exitCode(checks))
    }

    @Test
    fun `every validate error is its own failure`() {
        val c = validConfig()
        val bad = c.copy(mcpPort = c.appApiPort, agents = listOf(c.agents.single().copy(role = "BOSS")))
        val checks = Doctor.run(bad, env())
        val fails = checks.fails()
        assertTrue(fails.any { "must differ" in it.message }, checks.text())
        assertTrue(fails.any { "role must be one of" in it.message }, checks.text())
        assertEquals(1, Doctor.exitCode(checks))
    }

    @Test
    fun `onnx-local model warns when missing and is ok when present, without writing`() {
        val cache = Files.createDirectories(tmp.resolve("model-cache"))
        val config = validConfig().copy(embedder = SvodConfig.EmbedderSettings(provider = "onnx-local"))
        val missing = Doctor.run(config, env(modelCache = cache))
        assertTrue(missing.warns().any { "model not downloaded" in it.message }, missing.text())
        assertTrue(missing.fails().isEmpty(), missing.text())
        assertEquals(0, Files.list(cache).use { it.count() })
        assertFalse(Files.exists(config.vault().resolve(".svod")))

        val dir = Files.createDirectories(cache.resolve(config.embedder.onnxModelId))
        Files.writeString(dir.resolve(ModelManager.MODEL_FILE), "m")
        Files.writeString(dir.resolve(ModelManager.TOKENIZER_FILE), "t")
        val present = Doctor.run(config, env(modelCache = cache))
        assertTrue(present.any { it.status == Status.OK && it.name == "embedder" }, present.text())
        assertFalse(present.any { "model not downloaded" in it.message }, present.text())

        val badLocal = config.copy(embedder = config.embedder.copy(onnxLocalPath = tmp.resolve("no-model").toString()))
        assertTrue(Doctor.run(badLocal, env(modelCache = cache)).fails().any { it.name == "embedder" })
    }

    @Test
    fun `an onnx-local model with no pinned download fails`() {
        val config = validConfig().copy(embedder = SvodConfig.EmbedderSettings(provider = "onnx-local", onnxModelId = "not-bundled"))
        val checks = Doctor.run(config, env())
        assertTrue(checks.fails().any { it.name == "embedder" && "not-bundled" in it.message }, checks.text())
    }

    @Test
    fun `an embedder apiKeyRef the provider never reads is not checked`() {
        val unused = validConfig().copy(embedder = SvodConfig.EmbedderSettings(provider = "none", apiKeyRef = "env:SVOD_UNSET_KEY"))
        assertTrue(Doctor.run(unused, env()).fails().isEmpty())
        val used = unused.copy(embedder = unused.embedder.copy(provider = "openai"))
        assertTrue(Doctor.run(used, env()).fails().any { "embedder.apiKeyRef" in it.message })
    }

    @Test
    fun `config file problems are one failure`() {
        val (none, missing) = Doctor.checkConfigFile(tmp.resolve("absent.json"))
        assertEquals(null, none)
        assertEquals(Status.FAIL, missing.single().status)
        assertTrue("svod-engine init" in (missing.single().fix ?: ""), missing.toString())
        assertEquals(1, Doctor.exitCode(missing))

        val bad = tmp.resolve("bad.json").also { Files.writeString(it, "{ not json") }
        val (none2, malformed) = Doctor.checkConfigFile(bad)
        assertEquals(null, none2)
        assertEquals(Status.FAIL, malformed.single().status)
        assertEquals(1, Doctor.exitCode(malformed))

        val good = tmp.resolve("good.json").also { Files.writeString(it, """{"vaultPath":"/tmp/v"}""") }
        val (loaded, ok) = Doctor.checkConfigFile(good)
        assertEquals("/tmp/v", loaded?.vaultPath)
        assertTrue(ok.all { it.status == Status.OK }, ok.toString())
    }
}
