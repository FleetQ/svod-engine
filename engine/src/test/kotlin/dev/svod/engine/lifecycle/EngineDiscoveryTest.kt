package dev.svod.engine.lifecycle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EngineDiscoveryTest {

    @Test
    fun `writes the actual ports and label and replaces an older file`() {
        val dir = Files.createTempDirectory("svod-discovery")
        val file = dir.resolve("nested/engine.json")
        EngineDiscovery.write(file, "127.0.0.1", 7517, 7518, pid = 1, launchdLabel = "old")
        EngineDiscovery.write(file, "127.0.0.1", 7619, 7620, pid = 42, launchdLabel = "com.example.svod")

        val o = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("127.0.0.1", o["host"]!!.jsonPrimitive.content)
        assertEquals(7619, o["appApiPort"]!!.jsonPrimitive.int)
        assertEquals(7620, o["mcpPort"]!!.jsonPrimitive.int)
        assertEquals(42L, o["pid"]!!.jsonPrimitive.long)
        assertEquals("com.example.svod", o["launchdLabel"]!!.jsonPrimitive.content)
        assertEquals(listOf("engine.json"), Files.list(file.parent).map { it.fileName.toString() }.toList())
    }

    @Test
    fun `omits the label when not running under launchd`() {
        val file = Files.createTempDirectory("svod-discovery").resolve("engine.json")
        EngineDiscovery.write(file, "127.0.0.1", 7517, 7518, pid = 1, launchdLabel = null)
        assertFalse(Json.parseToJsonElement(Files.readString(file)).jsonObject.containsKey("launchdLabel"))
    }

    @Test
    fun `label comes from XPC_SERVICE_NAME`() {
        assertEquals("dev.svod.engine", EngineDiscovery.launchdLabel(mapOf("XPC_SERVICE_NAME" to "dev.svod.engine")))
        assertNull(EngineDiscovery.launchdLabel(mapOf("XPC_SERVICE_NAME" to "0")))
        assertNull(EngineDiscovery.launchdLabel(emptyMap()))
    }
}
