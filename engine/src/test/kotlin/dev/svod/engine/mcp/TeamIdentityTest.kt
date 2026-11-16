package dev.svod.engine.mcp

import dev.svod.engine.lifecycle.SvodConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ADR-0020 decision 1: a user-bound agent commits as the person, with a `Svod-Agent` trailer. */
class TeamIdentityTest {

    private val bound = AgentRegistry.AgentSpec(
        "bound-token", "scribe", AgentRole.WRITE,
        name = "Nikola K", email = "nikola@team.test", userId = "nikola",
    )

    private fun heads(fx: McpFixture, n: Int): List<RevCommit> =
        Git.open(fx.root.toFile()).use { g -> g.log().setMaxCount(n).call().toList() }

    private fun head(fx: McpFixture): RevCommit = heads(fx, 1).single()

    private fun assertBound(c: RevCommit) {
        assertEquals("Nikola K", c.authorIdent.name)
        assertEquals("nikola@team.test", c.authorIdent.emailAddress)
        assertEquals(listOf("scribe"), c.getFooterLines("Svod-Agent"))
    }

    @Test
    fun `every mutating tool commits as the person with the agent trailer`() = runBlocking {
        McpFixture(agents = listOf(bound)).use { fx ->
            val a = fx.write
            fx.tools.write(a, "n/a.md", "one", null); assertBound(head(fx))
            val rev = fx.engine.read("n/a.md")!!.revision
            fx.tools.edit(a, "n/a.md", "one", "two", false, rev); assertBound(head(fx))
            fx.tools.move(a, "n/a.md", "n/b.md", null); assertBound(head(fx))
            fx.tools.delete(a, "n/b.md", null); assertBound(head(fx))
            fx.tools.write(a, "messy/m.md", "draft", null)
            fx.tools.promote(a, "messy/m.md", "kept/m.md", null); assertBound(head(fx))
            val r = fx.tools.remember(a, "Prod DB is in us-east-1.", "fact", "infra", null, null, null, null, null)
            assertEquals("written", r.data["status"]?.jsonPrimitive?.content)
            assertBound(head(fx))
        }
    }

    @Test
    fun `remember classifies NEW then DUPLICATE for a bound agent`() = runBlocking {
        McpFixture(agents = listOf(bound)).use { fx ->
            val first = fx.tools.remember(fx.write, "The deploy host is friday.", "fact", "infra", null, null, null, null, null)
            assertEquals("NEW", first.data["classification"]?.jsonPrimitive?.content)
            assertBound(head(fx))
            val before = head(fx).name
            val again = fx.tools.remember(fx.write, "The deploy host is friday.", "fact", "infra", null, null, null, null, null)
            assertEquals("DUPLICATE", again.data["classification"]?.jsonPrimitive?.content)
            assertEquals(before, head(fx).name, "a duplicate writes nothing")
        }
    }

    @Test
    fun `an unbound agent commits exactly as before - agent author, no trailer`() = runBlocking {
        McpFixture().use { fx ->
            fx.tools.write(fx.write, "n/a.md", "one", null)
            val c = head(fx)
            assertEquals("Scribe", c.authorIdent.name)
            assertEquals("scribe@agents.test", c.authorIdent.emailAddress)
            assertEquals("write: n/a.md", c.fullMessage)
            assertTrue(c.getFooterLines("Svod-Agent").isEmpty())
        }
    }

    @Test
    fun `token resolves to the user and the person's author carries the agent id`() {
        val dir = java.nio.file.Files.createTempDirectory("svod-team-id-")
        val cfg = SvodConfig(
            vaultPath = dir.toString(),
            users = listOf(SvodConfig.UserSettings("nikola", "Nikola K", "nikola@team.test", "env:UNUSED")),
            agents = listOf(
                SvodConfig.AgentSettings("tok-bound", "scribe", "WRITE", user = "nikola"),
                SvodConfig.AgentSettings("tok-free", "robot", "WRITE"),
            ),
        )
        val registry = AgentRegistry(cfg.toAgentSpecs())
        val id = assertNotNull(registry.authenticate("tok-bound"))
        assertEquals("nikola", id.userId)
        assertEquals("Nikola K", id.author.name)
        assertEquals("nikola@team.test", id.author.email)
        assertEquals("scribe", id.author.agent)
        val free = assertNotNull(registry.authenticate("tok-free"))
        assertEquals(null, free.userId)
        assertEquals(null, free.author.agent)
    }
}
