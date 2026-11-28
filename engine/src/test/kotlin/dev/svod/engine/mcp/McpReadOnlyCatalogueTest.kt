package dev.svod.engine.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A global READ_ONLY agent is only offered the reader tools on the stateless tools/list. */
class McpReadOnlyCatalogueTest {

    private suspend fun HttpClient.toolNames(port: Int, token: String): List<String> {
        val meta = """"_meta":{"${McpProtocol.META_PROTOCOL_VERSION}":"${McpProtocol.V2026_07_28}",""" +
            """"${McpProtocol.META_CLIENT_INFO}":{"name":"catalogue-test","version":"1.0.0"},""" +
            """"${McpProtocol.META_CLIENT_CAPABILITIES}":{}}"""
        val res = post("http://127.0.0.1:$port/mcp") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{$meta}}""")
        }
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }

    @Test
    fun `read-only token lists only reader tools while write token lists all`(): Unit = runBlocking {
        McpFixture().use { fx ->
            val server = SvodMcpServer(fx.tools, fx.registry).start(0)
            try {
                HttpClient(ClientCIO).use { http ->
                    val readers = GrantResolver.REQUIRED.filterValues { it == GrantRole.READER }.keys
                    val readOnly = http.toolNames(server.port, READ_AGENT.token)
                    assertEquals(16, readOnly.size, "read-only sees 16 tools: $readOnly")
                    assertTrue("recent_changes" in readOnly, "recent_changes is a reader tool")
                    assertTrue("blame" in readOnly, "blame is a reader tool")
                    assertEquals(readers, readOnly.toSet())
                    listOf("write", "edit", "move", "delete", "remember", "extract_memories", "promote").forEach {
                        assertTrue(it !in readOnly, "$it must be hidden from a READ_ONLY agent")
                    }
                    val all = http.toolNames(server.port, WRITE_AGENT.token)
                    assertEquals(23, all.size, "write agent keeps all 23 tools: $all")
                    assertEquals(GrantResolver.REQUIRED.keys, all.toSet())
                }
            } finally { server.stop() }
        }
    }
}
