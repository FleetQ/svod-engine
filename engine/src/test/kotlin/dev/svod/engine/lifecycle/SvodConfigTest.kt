package dev.svod.engine.lifecycle

import dev.svod.engine.mcp.AgentRole
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvodConfigTest {

    private fun base() = SvodConfig(
        vaultPath = "/tmp/vault",
        agents = listOf(SvodConfig.AgentSettings("tok", "a1", "WRITE")),
    )

    @Test
    fun `a sane config validates`() {
        assertEquals(emptyList(), base().validate())
    }

    @Test
    fun `includeMessyInRecall defaults off and round-trips through JSON`() {
        assertEquals(false, base().includeMessyInRecall)
        val on = base().copy(includeMessyInRecall = true)
        val reloaded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(SvodConfig.serializer(), SvodConfig.toJson(on))
        assertTrue(reloaded.includeMessyInRecall, "toggle persists across serialize/deserialize")
    }

    @Test
    fun `bad port, non-loopback host, bad provider and duplicate tokens are rejected`() {
        val cfg = base().copy(
            host = "0.0.0.0",
            appApiPort = 70000,
            embedder = SvodConfig.EmbedderSettings(provider = "magic"),
            agents = listOf(
                SvodConfig.AgentSettings("dup", "a1", "WRITE"),
                SvodConfig.AgentSettings("dup", "a2", "nope"),
            ),
        )
        val errors = cfg.validate()
        assertTrue(errors.any { it.contains("loopback") }, errors.toString())
        assertTrue(errors.any { it.contains("appApiPort out of range") }, errors.toString())
        assertTrue(errors.any { it.contains("provider must be one of") }, errors.toString())
        assertTrue(errors.any { it.contains("tokens must be unique") }, errors.toString())
        assertTrue(errors.any { it.contains("role must be one of") }, errors.toString())
    }

    @Test
    fun `maps to embedder config and agent specs`() {
        val cfg = base().copy(embedder = SvodConfig.EmbedderSettings(provider = "none"))
        assertEquals(dev.svod.engine.index.EmbedderProvider.NONE, cfg.toEmbedderConfig().provider)
        val specs = cfg.toAgentSpecs()
        assertEquals(1, specs.size)
        assertEquals(AgentRole.WRITE, specs.first().role)
        assertEquals("a1@agents.svod.local", specs.first().email)
    }

    @Test
    fun `loads and round-trips JSON`() {
        val cfg = base()
        val file = Files.createTempFile("svod-config", ".json")
        Files.writeString(file, SvodConfig.toJson(cfg))
        assertEquals(cfg, SvodConfig.loadOrThrowValidated(file))
    }

    @Test
    fun `memory extraction defaults to none`() {
        val cfg = base()
        assertEquals("none", cfg.memory.extractionProvider)
        val ex = cfg.toMemoryExtractorConfig()
        assertEquals(dev.svod.engine.memory.MemoryExtractionProvider.NONE, ex.provider)
        assertEquals(20000, ex.maxTranscriptChars)
        assertEquals(false, dev.svod.engine.memory.MemoryExtractors.create(ex).isActive)
    }

    @Test
    fun `memory extraction provider parses from JSON and reuses the embedder endpoint`() {
        val file = Files.createTempFile("svod-config-extract", ".json")
        Files.writeString(
            file,
            """{"vaultPath": "/tmp/vault", "agents": [{"token": "tok", "agentId": "a1", "role": "WRITE"}],
               "embedder": {"provider": "none", "endpoint": "http://127.0.0.1:12345"},
               "memory": {"extractionProvider": "ollama", "extractionModel": "small-model"}}""",
        )
        val cfg = SvodConfig.load(file)
        assertEquals(false, cfg.memory.requireReview, "old keys keep their defaults")
        val ex = cfg.toMemoryExtractorConfig()
        assertEquals(dev.svod.engine.memory.MemoryExtractionProvider.OLLAMA, ex.provider)
        assertEquals("small-model", ex.model)
        assertEquals("http://127.0.0.1:12345", ex.endpoint)
        assertTrue(dev.svod.engine.memory.MemoryExtractors.create(ex).isActive)
    }

    @Test
    fun `agent tools allowlist parses and flows into agent specs`() {
        val file = Files.createTempFile("svod-config-tools", ".json")
        Files.writeString(
            file,
            """{"vaultPath": "/tmp/vault", "agents": [{"token": "tok", "agentId": "a1", "role": "WRITE", "tools": ["search", "remember"]}]}""",
        )
        val cfg = SvodConfig.load(file)
        assertEquals(listOf("search", "remember"), cfg.agents.single().tools)
        assertEquals(emptyList(), cfg.validate())
        val spec = cfg.toAgentSpecs().single()
        assertEquals(listOf("search", "remember"), spec.tools)
        val identity = dev.svod.engine.mcp.AgentRegistry(listOf(spec)).byAgentId("a1")!!
        assertEquals(setOf("search", "remember"), identity.tools)
    }

    @Test
    fun `unknown or empty tools allowlists are rejected, absent means all`() {
        val unknown = base().copy(agents = listOf(SvodConfig.AgentSettings("tok", "x", "WRITE", tools = listOf("search", "serch"))))
        val errors = unknown.validate()
        assertTrue(errors.any { it.startsWith("agent 'x' lists unknown tool 'serch'") }, errors.toString())
        assertEquals(1, errors.count { "unknown tool" in it }, "only the misspelt name is reported: $errors")
        val empty = base().copy(agents = listOf(SvodConfig.AgentSettings("tok", "x", "WRITE", tools = emptyList())))
        assertTrue(empty.validate().any { "agent 'x' tools must list at least one tool" in it }, empty.validate().toString())
        assertEquals(null, base().agents.single().tools)
        assertEquals(null, base().toAgentSpecs().single().tools)
        assertTrue("\"tools\"" !in SvodConfig.toJson(base()), "an absent allowlist is not written out")
    }

    @Test
    fun `a config without the new memory keys still loads`() {
        val file = Files.createTempFile("svod-config-old-memory", ".json")
        Files.writeString(
            file,
            """{"vaultPath": "/tmp/vault", "agents": [{"token": "tok", "agentId": "a1", "role": "WRITE"}],
               "memory": {"requireReview": true}}""",
        )
        val cfg = SvodConfig.load(file)
        assertTrue(cfg.memory.requireReview)
        assertEquals("none", cfg.memory.extractionProvider)
        assertEquals(cfg.embedder.ollamaEndpoint, cfg.toMemoryExtractorConfig().endpoint)
    }
}

class AdminKeyWarningTest {

    private fun warningsFor(users: List<SvodConfig.UserSettings>): List<String> {
        val logger = org.slf4j.LoggerFactory.getLogger(SvodConfig::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            SvodConfig(vaultPath = "/tmp/vault", users = users).toUserSpecs()
        } finally { logger.detachAppender(appender) }
        return appender.list.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun `an admin whose key file is missing gets a loud warning, a plain user a normal one`() {
        val missing = "file:/nonexistent/svod-test/user.key"
        val warnings = warningsFor(
            listOf(
                SvodConfig.UserSettings("boss", "Boss", keyRef = missing, admin = true),
                SvodConfig.UserSettings("pleb", "Pleb", keyRef = missing),
            ),
        )
        assertEquals(2, warnings.size, warnings.toString())
        assertTrue(warnings.single { "'boss'" in it }.startsWith("ADMIN user 'boss' skipped"), warnings.toString())
        assertTrue(warnings.single { "'pleb'" in it }.startsWith("user 'pleb' skipped"), warnings.toString())
    }
}
