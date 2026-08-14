package dev.svod.engine.lifecycle

import dev.svod.engine.api.AgentAdmin
import dev.svod.engine.api.CreateAgentRequest
import dev.svod.engine.api.UpdateAgentRequest
import dev.svod.engine.mcp.AgentRegistry
import dev.svod.engine.mcp.AgentRole
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentAdminTest {

    // A file: ref containing a known literal — lets Secrets.resolve return the token value.
    private fun tokenRef(value: String): String {
        val f = Files.createTempFile("svod-test-token-", ".txt")
        f.toFile().deleteOnExit()
        Files.writeString(f, value)
        return "file:$f"
    }

    private fun controller(): Triple<AgentController, AgentRegistry, ConfigStore> {
        val config = SvodConfig(vaultPath = "/tmp/test-vault", mcpPort = 7518)
        val store = ConfigStore(config, null) // null = in-memory only
        val registry = AgentRegistry(emptyList())
        val ctrl = AgentController(store, registry, "127.0.0.1")
        return Triple(ctrl, registry, store)
    }

    @Test
    fun `create valid agent — registry hot-reloads without rebuilding the node`() = runBlocking {
        val (ctrl, registry, _) = controller()
        val tokenValue = "secret-token-abc"
        val ref = tokenRef(tokenValue)

        val view = ctrl.create(CreateAgentRequest(agentId = "my-agent", role = "READ_ONLY", tokenRef = ref))

        assertEquals("my-agent", view.agentId)
        assertEquals("READ_ONLY", view.role)
        // The registry must authenticate without any node rebuild.
        val identity = registry.authenticate(tokenValue)
        assertNotNull(identity)
        assertEquals("my-agent", identity.agentId)
        assertEquals(AgentRole.READ_ONLY, identity.role)
    }

    @Test
    fun `duplicate agent id — Conflict`(): Unit = runBlocking {
        val (ctrl, _, _) = controller()
        val ref = tokenRef("token-1")
        ctrl.create(CreateAgentRequest(agentId = "dup", role = "READ_ONLY", tokenRef = ref))
        val ref2 = tokenRef("token-2")
        assertFailsWith<AgentAdmin.Conflict> {
            ctrl.create(CreateAgentRequest(agentId = "dup", role = "READ_ONLY", tokenRef = ref2))
        }
    }

    @Test
    fun `invalid agent id pattern — InvalidRequest`(): Unit = runBlocking {
        val (ctrl, _, _) = controller()
        assertFailsWith<AgentAdmin.InvalidRequest> {
            ctrl.create(CreateAgentRequest(agentId = "Bad ID!", role = "READ_ONLY", tokenRef = tokenRef("x")))
        }
    }

    @Test
    fun `raw token (not a Secrets ref) — NotARef`(): Unit = runBlocking {
        val (ctrl, _, _) = controller()
        assertFailsWith<AgentAdmin.NotARef> {
            ctrl.create(CreateAgentRequest(agentId = "agent1", role = "READ_ONLY", tokenRef = "raw-literal-token"))
        }
    }

    @Test
    fun `update role and vaults — registry reflects changes`() = runBlocking {
        val (ctrl, registry, _) = controller()
        val tokenValue = "update-test-token"
        val ref = tokenRef(tokenValue)
        ctrl.create(CreateAgentRequest(agentId = "updatable", role = "READ_ONLY", tokenRef = ref))

        ctrl.update("updatable", UpdateAgentRequest(role = "WRITE", vaults = listOf("vault-a")))

        val identity = registry.authenticate(tokenValue)
        assertNotNull(identity)
        assertEquals(AgentRole.WRITE, identity.role)
        assertEquals(listOf("vault-a"), identity.vaults)
    }

    @Test
    fun `delete — authenticate returns null, second delete — UnknownAgent`(): Unit = runBlocking {
        val (ctrl, registry, _) = controller()
        val tokenValue = "delete-test-token"
        val ref = tokenRef(tokenValue)
        ctrl.create(CreateAgentRequest(agentId = "to-delete", role = "READ_ONLY", tokenRef = ref))
        assertNotNull(registry.authenticate(tokenValue))

        ctrl.delete("to-delete")
        assertNull(registry.authenticate(tokenValue))

        assertFailsWith<AgentAdmin.UnknownAgent> { ctrl.delete("to-delete") }
    }

    @Test
    fun `list — carries mcpPort`() {
        val (ctrl, _, store) = controller()
        val view = ctrl.list()
        assertEquals(store.config.mcpPort, view.mcpPort)
    }

    @Test
    fun `update unknown agent — UnknownAgent`(): Unit = runBlocking {
        val (ctrl, _, _) = controller()
        assertFailsWith<AgentAdmin.UnknownAgent> {
            ctrl.update("ghost", UpdateAgentRequest(role = "WRITE"))
        }
    }

    @Test
    fun `create with prompt — prompt survives round-trip`() = runBlocking {
        val (ctrl, _, store) = controller()
        val ref = tokenRef("prompt-token")
        ctrl.create(CreateAgentRequest(
            agentId = "prompted",
            role = "WRITE",
            tokenRef = ref,
            prompt = "You are a helpful assistant.",
        ))
        val saved = store.config.agents.first { it.agentId == "prompted" }
        assertEquals("You are a helpful assistant.", saved.prompt)
    }

    @Test
    fun `zero agents is allowed after deleting the last one`() = runBlocking {
        val (ctrl, registry, store) = controller()
        val ref = tokenRef("solo-token")
        ctrl.create(CreateAgentRequest(agentId = "solo", role = "READ_ONLY", tokenRef = ref))
        ctrl.delete("solo")
        assertTrue(store.config.agents.isEmpty())
        val view = ctrl.list()
        assertTrue(view.agents.isEmpty())
    }
}
