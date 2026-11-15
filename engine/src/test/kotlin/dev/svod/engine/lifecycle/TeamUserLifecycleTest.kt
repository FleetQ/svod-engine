package dev.svod.engine.lifecycle

import dev.svod.engine.api.CreateUserRequest
import dev.svod.engine.api.UpdateUserRequest
import dev.svod.engine.api.UserAdmin
import dev.svod.engine.api.UserRegistry
import dev.svod.engine.mcp.AgentRegistry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ADR-0020: user lifecycle keeps the bound agents' authorship in step. */
class TeamUserLifecycleTest {

    private class Rig {
        val dir = Files.createTempDirectory("svod-team-users-")
        val store = ConfigStore(
            SvodConfig(
                vaults = listOf(SvodConfig.VaultSettings("a", dir.resolve("a").toString())),
                agents = listOf(
                    SvodConfig.AgentSettings("tok-bound", "scribe", "WRITE"),
                    SvodConfig.AgentSettings("tok-free", "robot", "WRITE"),
                ),
            ),
            null,
        )
        val agents = AgentRegistry(store.config.toAgentSpecs())
        val ctrl = UserController(store, UserRegistry(emptyList()), dir.resolve("secrets"), agentRegistry = agents)

        fun bind(agentId: String, userId: String) = store.update { c ->
            c.copy(agents = c.agents.map { if (it.agentId == agentId) it.copy(user = userId) else it })
        }.also { agents.reload(store.config.toAgentSpecs()) }
    }

    @Test
    fun `renaming a person updates the author of their bound agents`(): Unit = runBlocking {
        val r = Rig()
        r.ctrl.create(CreateUserRequest("nikola", "Nikola", email = "n@team.test"))
        r.bind("scribe", "nikola")
        assertEquals("Nikola", assertNotNull(r.agents.authenticate("tok-bound")).author.name)

        r.ctrl.update("nikola", UpdateUserRequest(name = "Nikola K"))
        val a = assertNotNull(r.agents.authenticate("tok-bound")).author
        assertEquals("Nikola K", a.name)
        assertEquals("scribe", a.agent)
    }

    @Test
    fun `deleting a bound person is a conflict and leaves config unchanged`(): Unit = runBlocking {
        val r = Rig()
        r.ctrl.create(CreateUserRequest("nikola", "Nikola"))
        r.bind("scribe", "nikola")
        val before = r.store.config
        val e = assertFailsWith<UserAdmin.Conflict> { r.ctrl.delete("nikola") }
        assertTrue(e.message!!.contains("bound to agent(s) [scribe]"), e.message)
        assertEquals(before, r.store.config)
    }

    @Test
    fun `deleting an unbound person still works`(): Unit = runBlocking {
        val r = Rig()
        r.ctrl.create(CreateUserRequest("maria", "Maria"))
        r.ctrl.delete("maria")
        assertTrue(r.store.config.users.isEmpty())
    }
}
