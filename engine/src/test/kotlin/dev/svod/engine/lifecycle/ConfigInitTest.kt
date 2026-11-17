package dev.svod.engine.lifecycle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfigInitTest {

    @Test
    fun `init writes a config that loads through loadOrThrowValidated`() {
        val dir = Files.createTempDirectory("svod-init")
        val result = ConfigInit.write(dir, "none")
        assertEquals(dir.resolve("config.json"), result.configPath)
        assertTrue(Files.isRegularFile(result.configPath))

        val loaded = SvodConfig.loadOrThrowValidated(result.configPath)
        assertEquals(dir.resolve("vault").toAbsolutePath().toString(), loaded.vaultPath)
        assertEquals("127.0.0.1", loaded.host)
        assertEquals(7517, loaded.appApiPort)
        assertEquals(7518, loaded.mcpPort)
        assertEquals("none", loaded.embedder.provider)
        assertTrue(loaded.secretScanning)
        val agent = loaded.agents.single()
        assertEquals("WRITE", agent.role)
        assertEquals(result.token, agent.token)
    }

    @Test
    fun `onnx-local is accepted and an unknown embedder is rejected`() {
        val dir = Files.createTempDirectory("svod-init")
        val onnx = ConfigInit.write(dir, "onnx-local")
        val loaded = SvodConfig.loadOrThrowValidated(onnx.configPath)
        assertEquals("onnx-local", loaded.embedder.provider)

        val other = Files.createTempDirectory("svod-init")
        assertFailsWith<IllegalArgumentException> { ConfigInit.write(other, "magic") }
        assertFalse(Files.exists(other.resolve("config.json")))
    }

    @Test
    fun `the agent token is random`() {
        val a = ConfigInit.write(Files.createTempDirectory("svod-init")).token
        val b = ConfigInit.write(Files.createTempDirectory("svod-init")).token
        assertNotEquals(a, b)
        for (t in listOf(a, b)) {
            assertTrue(t.startsWith(ConfigInit.TOKEN_PREFIX), t)
            assertTrue(t.length >= 40, "token too short: ${t.length}")
            assertNotEquals("friday-write-token", t)
        }
    }

    @Test
    fun `an existing config is never overwritten`() {
        val dir = Files.createTempDirectory("svod-init")
        val path = dir.resolve("config.json")
        val sentinel = """{"vaultPath":"/keep/me"}""".toByteArray()
        Files.write(path, sentinel)

        val e = assertFailsWith<IllegalArgumentException> { ConfigInit.write(dir, "none") }
        assertTrue(e.message!!.contains("not overwriting"), e.message)
        assertContentEquals(sentinel, Files.readAllBytes(path))
    }

    @Test
    fun `a dir path that is a regular file is rejected`() {
        val file = Files.createTempFile("svod-init", ".txt")
        assertFailsWith<IllegalArgumentException> { ConfigInit.write(file, "none") }
    }

    @Test
    fun `config file is owner-only on POSIX`() {
        val dir = Files.createTempDirectory("svod-init")
        if (Files.getFileAttributeView(dir, PosixFileAttributeView::class.java) == null) return
        val result = ConfigInit.write(dir)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(result.configPath),
        )
    }

    @Test
    fun `client setup text carries the real endpoint and token`() {
        val dir = Files.createTempDirectory("svod-init")
        val result = ConfigInit.write(dir)
        val text = ConfigInit.clientSetup(result.config, result.token)

        val json = ConfigInit.mcpServersJson(result.config, result.token)
        assertTrue(text.contains(json), "setup text embeds the mcpServers block")
        val server = Json.parseToJsonElement(json).jsonObject["mcpServers"]!!.jsonObject["svod-memory"]!!.jsonObject
        assertEquals("http://127.0.0.1:7518/mcp", server["url"]!!.jsonPrimitive.content)
        assertEquals(
            "Bearer ${result.token}",
            server["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content,
        )

        assertTrue(
            text.contains(
                "claude mcp add --transport http svod-memory http://127.0.0.1:7518/mcp " +
                    "--header \"Authorization: Bearer ${result.token}\"",
            ),
            text,
        )
        assertTrue(text.contains(ConfigInit.SYSTEM_PROMPT))
        assertTrue(ConfigInit.SYSTEM_PROMPT.startsWith("You have a persistent, git-backed memory called Svod"))
    }
}
