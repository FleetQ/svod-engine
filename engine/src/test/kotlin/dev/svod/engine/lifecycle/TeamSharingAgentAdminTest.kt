package dev.svod.engine.lifecycle

import dev.svod.engine.api.AgentAdmin
import dev.svod.engine.api.CreateAgentRequest
import dev.svod.engine.api.UpdateAgentRequest
import dev.svod.engine.mcp.AgentRegistry
import dev.svod.engine.mcp.GrantRole
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** S0 (ADR-0020): user binding and per-vault roles through the agents admin API. */
class TeamSharingAgentAdminTest {

    private fun tokenRef(value: String): String {
        val f = Files.createTempFile("svod-test-token-", ".txt")
        f.toFile().deleteOnExit()
        Files.writeString(f, value)
        return "file:$f"
    }

    private fun controller(): Triple<AgentController, AgentRegistry, ConfigStore> {
        val config = SvodConfig(
            vaults = listOf(SvodConfig.VaultSettings("team", "/tmp/team"), SvodConfig.VaultSettings("notes", "/tmp/notes")),
            users = listOf(SvodConfig.UserSettings(userId = "nikola", name = "Nikola", email = "n@example.com", keyRef = "file:/nonexistent/n.key")),
        )
        val store = ConfigStore(config, null)
        val registry = AgentRegistry(emptyList())
        return Triple(AgentController(store, registry, "127.0.0.1"), registry, store)
    }

    @Test
    fun `create binds a user and roles, and the registry sees the person`(): Unit = runBlocking {
        val (ctrl, registry, store) = controller()
        val view = ctrl.create(
            CreateAgentRequest(
                agentId = "nk", role = "WRITE", tokenRef = tokenRef("tok-nk"),
                vaults = listOf("team", "notes"), user = "nikola", vaultRoles = mapOf("team" to "maintainer"),
            ),
        )
        assertEquals("nikola", view.user)
        assertEquals(mapOf("team" to "maintainer"), view.vaultRoles)
        assertEquals(emptyList(), store.config.validate())
        val id = registry.authenticate("tok-nk")!!
        assertEquals("nikola", id.userId)
        assertEquals("Nikola", id.author.name)
        assertEquals(mapOf("team" to GrantRole.MAINTAINER), id.grantRoles)
    }

    @Test
    fun `create rejects an unknown user, a bad role and a role for an ungranted vault`(): Unit = runBlocking {
        val (ctrl, _, _) = controller()
        assertFailsWith<AgentAdmin.InvalidRequest> {
            ctrl.create(CreateAgentRequest(agentId = "a", role = "WRITE", tokenRef = tokenRef("t1"), user = "ghost"))
        }
        assertFailsWith<AgentAdmin.InvalidRequest> {
            ctrl.create(CreateAgentRequest(agentId = "b", role = "WRITE", tokenRef = tokenRef("t2"), vaults = listOf("team"), vaultRoles = mapOf("team" to "owner")))
        }
        assertFailsWith<AgentAdmin.InvalidRequest> {
            ctrl.create(CreateAgentRequest(agentId = "c", role = "WRITE", tokenRef = tokenRef("t3"), vaults = listOf("team"), vaultRoles = mapOf("notes" to "reader")))
        }
    }

    @Test
    fun `update keeps omitted fields, unbinds on empty user and drops roles of removed vaults`(): Unit = runBlocking {
        val (ctrl, _, store) = controller()
        ctrl.create(
            CreateAgentRequest(
                agentId = "nk", role = "WRITE", tokenRef = tokenRef("tok-u"),
                vaults = listOf("team", "notes"), user = "nikola", vaultRoles = mapOf("team" to "reader", "notes" to "maintainer"),
            ),
        )
        val renamed = ctrl.update("nk", UpdateAgentRequest(name = "x"))
        assertEquals("nikola", renamed.user)
        assertEquals(mapOf("team" to "reader", "notes" to "maintainer"), renamed.vaultRoles)

        val narrowed = ctrl.update("nk", UpdateAgentRequest(vaults = listOf("team")))
        assertEquals(mapOf("team" to "reader"), narrowed.vaultRoles)

        val unbound = ctrl.update("nk", UpdateAgentRequest(user = ""))
        assertNull(unbound.user)
        assertEquals(emptyList(), store.config.validate())
    }
}
