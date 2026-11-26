package dev.svod.engine.mcp

import dev.svod.engine.core.Author
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrantResolverTest {
    private fun agent(
        role: AgentRole = AgentRole.WRITE,
        user: String? = null,
        roles: Map<String, GrantRole> = emptyMap(),
        vaults: List<String> = emptyList(),
    ) = AgentIdentity("a1", role, Author("A", "a@x"), vaults, userId = user, grantRoles = roles)

    private val reads = listOf("read", "search", "context_pack", "list", "tree", "grep", "history", "diff", "get_revision", "link",
        "graph_query", "graph_communities", "graph_community", "graph_status")
    private val writes = listOf("write", "edit", "move", "delete", "remember", "extract_memories")

    @Test
    fun `table has exactly the ADR tools`() {
        assertEquals((reads + writes + "promote").toSet(), GrantResolver.REQUIRED.keys)
    }

    @Test
    fun `reader may only read`() {
        val a = agent(roles = mapOf("v" to GrantRole.READER))
        reads.forEach { assertNull(GrantResolver.deny(a, "v", it), it) }
        (writes + "promote").forEach { assertTrue(GrantResolver.deny(a, "v", it) != null, it) }
    }

    @Test
    fun `writer may read and write but not promote`() {
        val a = agent(roles = mapOf("v" to GrantRole.WRITER))
        (reads + writes).forEach { assertNull(GrantResolver.deny(a, "v", it), it) }
        assertTrue(GrantResolver.deny(a, "v", "promote") != null)
    }

    @Test
    fun `maintainer may call everything`() {
        val a = agent(roles = mapOf("v" to GrantRole.MAINTAINER))
        (reads + writes + "promote").forEach { assertNull(GrantResolver.deny(a, "v", it), it) }
    }

    @Test
    fun `bare grant of an opted-in agent is writer, also with no vault list`() {
        val bound = agent(user = "ann", vaults = listOf("v"))
        assertNull(GrantResolver.deny(bound, "v", "write"))
        assertTrue(GrantResolver.deny(bound, "v", "promote") != null)
        val mixed = agent(roles = mapOf("w" to GrantRole.MAINTAINER), vaults = listOf("v", "w"))
        assertNull(GrantResolver.deny(mixed, "v", "write"))
        assertTrue(GrantResolver.deny(mixed, "v", "promote") != null)
        assertNull(GrantResolver.deny(mixed, "w", "promote"))
    }

    @Test
    fun `READ_ONLY ceiling caps a maintainer grant`() {
        val a = agent(role = AgentRole.READ_ONLY, roles = mapOf("v" to GrantRole.MAINTAINER))
        reads.forEach { assertNull(GrantResolver.deny(a, "v", it), it) }
        (writes + "promote").forEach { assertTrue(GrantResolver.deny(a, "v", it) != null, it) }
    }

    @Test
    fun `an agent that did not opt in is never denied here`() {
        val a = agent(vaults = listOf("v"))
        (reads + writes + "promote" + "unknown_tool").forEach { assertNull(GrantResolver.deny(a, "v", it), it) }
    }

    @Test
    fun `an unknown tool needs maintainer`() {
        assertTrue(GrantResolver.deny(agent(user = "ann"), "v", "new_tool") != null)
        assertNull(GrantResolver.deny(agent(roles = mapOf("v" to GrantRole.MAINTAINER)), "v", "new_tool"))
    }

    @Test
    fun `advertised hides only non-reader tools and only from READ_ONLY agents`() {
        val writer = agent(roles = mapOf("v" to GrantRole.READER))
        (reads + writes + "promote").forEach { assertTrue(GrantResolver.advertised(writer, it), it) }
        assertTrue(GrantResolver.advertised(agent(), "promote"))
        val ro = agent(role = AgentRole.READ_ONLY, roles = mapOf("v" to GrantRole.MAINTAINER))
        reads.forEach { assertTrue(GrantResolver.advertised(ro, it), it) }
        (writes + "promote").forEach { assertFalse(GrantResolver.advertised(ro, it), it) }
        assertFalse(GrantResolver.advertised(agent(role = AgentRole.READ_ONLY), "unknown_tool"))
    }

    @Test
    fun `denial message names agent role vault and tool`() {
        val msg = GrantResolver.deny(agent(roles = mapOf("v" to GrantRole.READER)), "v", "write")
        assertEquals("agent 'a1' (role reader in vault 'v') may not call 'write'", msg)
    }
}
