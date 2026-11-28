package dev.svod.engine.api

import dev.svod.engine.core.Author
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.parser.OpenAPIV3Parser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Keeps the Claude Code hook scripts in dist/hooks/ and their guide honest. Each script declares the
 * App API routes and JSON fields it uses in `# svod-api: METHOD path fields=a,b` header lines; those are
 * compared with contract/openapi.yaml, the AppApiServer routes and a live engine. The scripts are never
 * run here (no bash, curl or jq needed).
 */
class ClaudeCodeHooksDocTest {

    private val engineDir: Path = Paths.get(System.getProperty("svod.projectDir") ?: System.getProperty("user.dir"))
    private val repoRoot: Path = engineDir.toAbsolutePath().parent

    private data class ApiUse(val script: String, val method: String, val path: String, val fields: List<String>)

    private fun read(rel: String): String {
        val p = repoRoot.resolve(rel)
        assertTrue(Files.isRegularFile(p), "$rel does not exist")
        return Files.readString(p)
    }

    private fun uses(script: String): List<ApiUse> =
        HEADER.findAll(read("dist/hooks/$script")).map {
            ApiUse(script, it.groupValues[1], it.groupValues[2], it.groupValues[3].split(',').map(String::trim).filter(String::isNotEmpty))
        }.toList()

    private val allUses: List<ApiUse> by lazy { SCRIPTS.flatMap { uses(it) } }

    private val openApi: OpenAPI by lazy { assertNotNull(OpenAPIV3Parser().read(repoRoot.resolve("contract/openapi.yaml").toString())) }

    private fun operation(method: String, path: String): Operation? {
        val item = openApi.paths[path] ?: return null
        return when (method) {
            "GET" -> item.get
            "POST" -> item.post
            "PUT" -> item.put
            "PATCH" -> item.patch
            "DELETE" -> item.delete
            else -> null
        }
    }

    private fun resolve(s: Schema<*>?): Schema<*>? {
        val ref = s?.`$ref` ?: return s
        return openApi.components.schemas[ref.substringAfterLast('/')]
    }

    /** Property name → required, over [s] and every object nested in it. */
    private fun props(s: Schema<*>?, out: MutableMap<String, Boolean> = HashMap(), seen: MutableSet<Schema<*>> = HashSet()): Map<String, Boolean> {
        val r = resolve(s) ?: return out
        if (!seen.add(r)) return out
        r.items?.let { props(it, out, seen) }
        val required = r.required.orEmpty().toSet()
        r.properties.orEmpty().forEach { (name, child) ->
            out[name] = (out[name] ?: false) || name in required
            props(child, out, seen)
        }
        return out
    }

    /** Fields of the operation's JSON request body and 200 response. */
    private fun contractFields(op: Operation): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        props(op.requestBody?.content?.get("application/json")?.schema, out)
        props(op.responses?.get("200")?.content?.get("application/json")?.schema, out)
        return out
    }

    private fun keys(e: JsonElement, out: MutableSet<String> = HashSet()): Set<String> {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> out += k; keys(v, out) }
            is JsonArray -> e.forEach { keys(it, out) }
            else -> {}
        }
        return out
    }

    @Test
    fun `hook scripts exist and declare their API use`() {
        for (script in SCRIPTS) {
            val text = read("dist/hooks/$script")
            assertTrue(text.startsWith("#!/usr/bin/env bash") || text.startsWith("#!/bin/bash"), "$script has no bash shebang")
            val declared = uses(script)
            assertTrue(declared.isNotEmpty(), "$script declares no '# svod-api:' header")
            for (u in declared) {
                assertTrue(u.fields.isNotEmpty(), "$script: ${u.method} ${u.path} lists no fields")
                assertTrue(text.contains(u.path), "$script declares ${u.path} but never uses it")
            }
            assertTrue(text.contains("exit 0"), "$script must end every path with exit 0")
        }
        val routes = allUses.map { "${it.method} ${it.path}" }.toSet()
        assertEquals(
            setOf("GET /api/v1/memory/rulebook", "GET /api/v1/changes", "POST /api/v1/memory/capture"),
            routes,
        )
        assertTrue(uses("svod-capture.sh").all { it.method == "POST" && it.path == "/api/v1/memory/capture" })
    }

    @Test
    fun `declared routes exist in the contract and the server`() {
        val server = Files.readString(engineDir.resolve("src/main/kotlin/dev/svod/engine/api/AppApiServer.kt"))
        for (u in allUses) {
            val op = assertNotNull(operation(u.method, u.path), "${u.script}: ${u.method} ${u.path} is not in contract/openapi.yaml")
            val route = "${u.method.lowercase()}(\"${u.path}\")"
            assertTrue(server.contains(route), "${u.script}: AppApiServer has no $route")
            val fields = contractFields(op)
            val unknown = u.fields.filter { it !in fields }
            assertTrue(unknown.isEmpty(), "${u.script}: ${u.method} ${u.path} has no contract field(s) $unknown")
        }
    }

    @Test
    fun `declared fields round-trip against a live engine`(): Unit = runBlocking {
        ApiFixture.create().use { fx ->
            fx.engine.write(
                "memory/policy/hooks.md",
                "---\ntype: policy\nstatus: active\ntitle: Hook rule\n---\nKeep hooks quiet.\n",
                null, Author("ui", "ui@svod.local"),
            )
            fx.index.waitIdle()

            val captureBody = buildJsonObject {
                put("sessionId", "sess-hooks-0001")
                put("project", "demo")
                put("transcript", "{\"type\":\"user\",\"message\":\"hi\"}")
                put("startedAt", 1000L)
                put("endedAt", 2000L)
            }
            val live = mapOf(
                "GET /api/v1/memory/rulebook" to fx.get("/api/v1/memory/rulebook"),
                "GET /api/v1/changes" to fx.get("/api/v1/changes?max=5"),
                "POST /api/v1/memory/capture" to fx.post("/api/v1/memory/capture", captureBody.toString()),
            )
            for (u in allUses) {
                val key = "${u.method} ${u.path}"
                val r = assertNotNull(live[key], "no live call for $key")
                assertEquals(200, r.statusCode(), "$key: ${r.body()}")
                val seen = keys(Json.parseToJsonElement(r.body())) + (if (u.method == "POST") captureBody.keys else emptySet())
                val required = contractFields(assertNotNull(operation(u.method, u.path)))
                val missing = u.fields.filter { it !in seen && required[it] == true }
                assertTrue(missing.isEmpty(), "$key: live exchange lacks required field(s) $missing (saw $seen)")
            }
            val book = Json.parseToJsonElement(live.getValue("GET /api/v1/memory/rulebook").body()).jsonObject
            assertEquals("Hook rule", book["items"]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content)
            val changes = Json.parseToJsonElement(live.getValue("GET /api/v1/changes").body()).jsonObject
            val head = changes["head"]!!.jsonPrimitive.content
            // the script passes the saved head back as `since`: that call must succeed
            assertEquals(200, fx.get("/api/v1/changes?max=20&since=$head").statusCode())
        }
    }

    @Test
    fun `guide matches the scripts`() {
        val guide = read(GUIDE)
        for (event in listOf("SessionStart", "Stop", "PreCompact", "SessionEnd")) {
            assertTrue(guide.contains(event), "$GUIDE does not name the $event hook event")
        }
        val block = JSON_FENCE.findAll(guide).map { it.groupValues[1] }.firstOrNull { it.contains("\"hooks\"") }
        assertNotNull(block, "$GUIDE has no ```json settings block with \"hooks\"")
        val hooks = Json.parseToJsonElement(block).jsonObject["hooks"]!!.jsonObject
        val expected = mapOf(
            "SessionStart" to "svod-session-start.sh",
            "Stop" to "svod-capture.sh",
            "PreCompact" to "svod-capture.sh",
            "SessionEnd" to "svod-capture.sh",
        )
        for ((event, script) in expected) {
            val entries = assertNotNull(hooks[event], "settings block has no hooks.$event").jsonArray
            val commands = entries.flatMap { e -> e.jsonObject["hooks"]!!.jsonArray.map { it.jsonObject } }
            assertTrue(commands.isNotEmpty(), "hooks.$event has no command")
            for (c in commands) {
                assertEquals("command", c["type"]!!.jsonPrimitive.content, "hooks.$event type")
                val cmd = c["command"]!!.jsonPrimitive.content
                assertTrue(cmd.startsWith("bash ") && cmd.endsWith(script), "hooks.$event runs '$cmd', expected 'bash …/$script'")
            }
        }
        val envVars = SCRIPTS.flatMap { ENV_VAR.findAll(read("dist/hooks/$it")).map { m -> m.value } }.toSet()
        assertTrue(envVars.isNotEmpty())
        val undocumented = envVars.filter { !guide.contains(it) }
        assertTrue(undocumented.isEmpty(), "$GUIDE does not document $undocumented")
        for (u in allUses) assertTrue(guide.contains(u.path), "$GUIDE does not mention ${u.path}")
    }

    @Test
    fun `README links the guide`() {
        val lines = read("README.md").lines()
        val start = lines.indexOfFirst { it.trim() == "### Connecting an LLM agent" }
        assertTrue(start >= 0, "README.md has no '### Connecting an LLM agent' section")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") || lines[it].startsWith("### ") } ?: lines.size
        val section = lines.subList(start, end).joinToString("\n")
        assertTrue(section.contains("](docs/claude-code-hooks.md)"), "the section does not link $GUIDE")
    }

    private companion object {
        const val GUIDE = "docs/claude-code-hooks.md"
        val SCRIPTS = listOf("svod-session-start.sh", "svod-capture.sh")
        val HEADER = Regex("""(?m)^# svod-api: (GET|POST|PUT|PATCH|DELETE) (/\S+) fields=(\S+)\s*$""")
        val JSON_FENCE = Regex("""(?s)```json\n(.*?)```""")
        val ENV_VAR = Regex("""\bSVOD_[A-Z_]+\b""")
    }
}
