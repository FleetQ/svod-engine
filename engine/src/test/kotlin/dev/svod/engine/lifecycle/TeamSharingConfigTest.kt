package dev.svod.engine.lifecycle

import dev.svod.engine.mcp.AgentRegistry
import dev.svod.engine.mcp.GrantRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S0 gate of team sharing (ADR-0020): the config schema for people bound to agents and roles on
 * grants. Legacy configs must parse, validate and serialize exactly as before.
 */
class TeamSharingConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun load(text: String): SvodConfig {
        val f = Files.createTempFile("svod-config", ".json")
        Files.writeString(f, text)
        return SvodConfig.load(f).also { Files.delete(f) }
    }

    private val teamConfig = """
        {
          "vaults": [{"id": "team", "path": "/tmp/team"}, {"id": "notes", "path": "/tmp/notes"}],
          "users": [
            {"userId": "nikola", "name": "Nikola K", "email": "nikola@example.com", "keyRef": "file:/s/nikola.key"},
            {"userId": "ana", "name": "Ana", "keyRef": "file:/s/ana.key"}
          ],
          "agents": [
            {"token": "t1", "agentId": "nk-claude", "role": "WRITE", "user": "nikola",
             "vaults": [{"vault": "team", "role": "maintainer"}, "notes"]},
            {"token": "t2", "agentId": "ana-claude", "role": "WRITE", "user": "ana",
             "vaults": [{"vault": "team", "role": "reader"}]},
            {"token": "t3", "agentId": "bot", "role": "WRITE", "vaults": ["team"]}
          ]
        }
    """.trimIndent()

    // ---- legacy configs: the gate ----

    @Test
    fun `every shipped sample config parses, validates and round-trips unchanged`() {
        val dist = File(System.getProperty("svod.projectDir") ?: ".").parentFile.resolve("dist")
        val samples = dist.listFiles { f -> f.name.startsWith("config.sample") && f.name.endsWith(".json") }.orEmpty()
        assertTrue(samples.isNotEmpty(), "no sample configs under ${dist.path}")
        for (sample in samples) {
            val original = json.parseToJsonElement(sample.readText())
            val cfg = SvodConfig.load(sample.toPath())
            assertTrue(cfg.agents.all { it.user == null && it.vaultRoles.isEmpty() }, "${sample.name}: no team fields appear")
            val reparsed = SvodConfig.load(Files.createTempFile("rt", ".json").also { Files.writeString(it, SvodConfig.toJson(cfg)) })
            assertEquals(cfg, reparsed, "${sample.name}: load → toJson → load is lossless")
            // Agents are written back in the legacy shape: string vault ids, no new keys.
            val agentsOut = json.parseToJsonElement(SvodConfig.toJson(cfg)).jsonObject["agents"]?.jsonArray ?: JsonArray(emptyList())
            for (a in agentsOut) {
                val o = a.jsonObject
                assertNull(o["user"], "${sample.name}: no user key written")
                assertNull(o["vaultRoles"], "${sample.name}: no vaultRoles key written")
                o["vaults"]?.jsonArray?.forEach { v -> assertTrue(v !is JsonObject, "${sample.name}: vault ids stay strings") }
            }
            val agentsIn = original.jsonObject["agents"]?.jsonArray ?: JsonArray(emptyList())
            assertEquals(agentsIn.size, agentsOut.size)
        }
    }

    @Test
    fun `a pre-ADR-0020 agent config yields the same spec as before`() {
        val cfg = load(
            """{"vaultPath": "/tmp/v", "agents": [{"token": "t", "agentId": "a1", "role": "WRITE", "name": "A One", "email": "a1@x"}]}""",
        )
        assertEquals(emptyList(), cfg.validate())
        val spec = cfg.toAgentSpecs().single()
        assertEquals(AgentRegistry.AgentSpec("t", "a1", dev.svod.engine.mcp.AgentRole.WRITE, "A One", "a1@x", listOf("default")), spec)
    }

    // ---- new schema ----

    @Test
    fun `object grants fold into vaults plus vaultRoles and bare ids carry no role`() {
        val cfg = load(teamConfig)
        assertEquals(emptyList(), cfg.validate())
        val nk = cfg.agents.first { it.agentId == "nk-claude" }
        assertEquals("nikola", nk.user)
        assertEquals(listOf("team", "notes"), nk.vaults)
        assertEquals(mapOf("team" to "maintainer"), nk.vaultRoles)
        val bot = cfg.agents.first { it.agentId == "bot" }
        assertNull(bot.user)
        assertEquals(emptyMap(), bot.vaultRoles)
    }

    @Test
    fun `an object grant without a role means writer`() {
        val cfg = load(
            """{"vaultPath": "/tmp/v", "agents": [{"token": "t", "agentId": "a", "role": "WRITE", "vaults": [{"vault": "default"}]}]}""",
        )
        assertEquals(mapOf("default" to "writer"), cfg.agents.single().vaultRoles)
    }

    @Test
    fun `the object form is written back as objects and reparses identically`() {
        val cfg = load(teamConfig)
        val out = SvodConfig.toJson(cfg)
        val agents = json.parseToJsonElement(out).jsonObject["agents"]!!.jsonArray
        val nkVaults = agents.first { it.jsonObject["agentId"]!!.jsonPrimitive.content == "nk-claude" }.jsonObject["vaults"]!!.jsonArray
        assertEquals("team", nkVaults[0].jsonObject["vault"]!!.jsonPrimitive.content)
        assertEquals("maintainer", nkVaults[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("notes", nkVaults[1].jsonPrimitive.content)
        assertTrue(agents.none { "vaultRoles" in it.jsonObject }, "vaultRoles never appears in the file")
        assertEquals(cfg, load(out))
    }

    @Test
    fun `a user-bound agent resolves to the person as author, an unbound one does not`() {
        val specs = load(teamConfig).toAgentSpecs().associateBy { it.agentId }
        val nk = specs.getValue("nk-claude")
        assertEquals("nikola", nk.userId)
        assertEquals("Nikola K", nk.name)
        assertEquals("nikola@example.com", nk.email)
        assertEquals(mapOf("team" to GrantRole.MAINTAINER), nk.grantRoles)
        val ana = specs.getValue("ana-claude")
        assertEquals("ana@users.svod.local", ana.email, "a person without email gets the users default")
        val bot = specs.getValue("bot")
        assertNull(bot.userId)
        assertEquals("bot", bot.name)
        assertEquals("bot@agents.svod.local", bot.email)
        assertEquals(emptyMap(), bot.grantRoles)
    }

    // ---- validation ----

    @Test
    fun `binding to an unknown user is a validation error`() {
        val cfg = load(teamConfig.replace("\"user\": \"ana\"", "\"user\": \"ghost\""))
        assertTrue(cfg.validate().any { it == "agent 'ana-claude' is bound to unknown user 'ghost'" }, cfg.validate().toString())
        assertTrue(cfg.startupErrors().any { "unknown user 'ghost'" in it }, "fatal at startup, even on loopback")
    }

    @Test
    fun `duplicate user ids are a validation error`() {
        val cfg = load(teamConfig.replace("\"userId\": \"ana\"", "\"userId\": \"nikola\""))
        assertTrue("user ids must be unique" in cfg.validate(), cfg.validate().toString())
    }

    @Test
    fun `an unknown grant role is a validation error`() {
        val cfg = load(teamConfig.replace("\"role\": \"reader\"", "\"role\": \"owner\""))
        assertTrue(
            cfg.validate().any { it == "agent 'ana-claude' role for vault 'team' must be one of [reader, writer, maintainer], was 'owner'" },
            cfg.validate().toString(),
        )
    }

    @Test
    fun `a role for a vault the agent is not granted is a validation error`() {
        val cfg = load(teamConfig).let { c ->
            c.copy(agents = c.agents.map { if (it.agentId == "bot") it.copy(vaultRoles = mapOf("notes" to "reader")) else it })
        }
        assertTrue(cfg.validate().any { it == "agent 'bot' has a role for vault 'notes' it is not granted" }, cfg.validate().toString())
    }

    @Test
    fun `a grant object without a vault id is rejected at parse time`() {
        assertFailsWith<kotlinx.serialization.SerializationException> {
            load("""{"vaultPath": "/tmp/v", "agents": [{"token": "t", "agentId": "a", "role": "WRITE", "vaults": [{"role": "reader"}]}]}""")
        }
    }

    @Test
    fun `grant role labels parse case-insensitively and are ordered`() {
        assertEquals(GrantRole.MAINTAINER, GrantRole.parse("Maintainer"))
        assertNull(GrantRole.parse("admin"))
        assertTrue(GrantRole.MAINTAINER.covers(GrantRole.WRITER))
        assertTrue(GrantRole.WRITER.covers(GrantRole.READER))
        assertTrue(!GrantRole.READER.covers(GrantRole.WRITER))
    }
}
