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
}
