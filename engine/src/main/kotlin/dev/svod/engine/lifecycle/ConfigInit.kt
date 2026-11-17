package dev.svod.engine.lifecycle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/**
 * `svod-engine init [dir]`: writes a new, valid `config.json` (vault in `<dir>/vault`, loopback
 * ports, one WRITE agent with a random token) and renders the snippets a new user pastes into an
 * MCP client. Never overwrites an existing config.
 */
object ConfigInit {
    const val TOKEN_PREFIX = "sva_"
    const val SERVER_NAME = "svod-memory"
    val EMBEDDERS = listOf("none", "onnx-local")

    private val random = SecureRandom()
    private val prettyJson = Json { prettyPrint = true }

    data class Result(val configPath: Path, val config: SvodConfig, val token: String)

    /** A 256-bit agent token, base64url without padding, with a recognisable prefix. */
    fun newToken(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun generate(dir: Path, embedder: String, token: String): SvodConfig {
        require(embedder in EMBEDDERS) { "--embedder must be one of $EMBEDDERS, was '$embedder'" }
        val config = SvodConfig(
            vaultPath = dir.resolve("vault").toAbsolutePath().toString(),
            host = "127.0.0.1",
            appApiPort = 7517,
            mcpPort = 7518,
            embedder = SvodConfig.EmbedderSettings(provider = embedder),
            secretScanning = true,
            agents = listOf(SvodConfig.AgentSettings(token = token, agentId = "agent", role = "WRITE", name = "My agent")),
        )
        val errors = config.validate()
        require(errors.isEmpty()) { "generated config is invalid:\n - " + errors.joinToString("\n - ") }
        return config
    }

    /**
     * Creates [dir] if needed and writes `<dir>/config.json` with CREATE_NEW, so an existing file is
     * never touched (no check-then-write race). On POSIX the file is created owner-only (rw-------).
     */
    fun write(dir: Path, embedder: String = "none"): Result {
        val token = newToken()
        val config = generate(dir, embedder, token)
        Files.createDirectories(dir)
        val path = dir.resolve("config.json")
        val bytes = SvodConfig.toJson(config).toByteArray(Charsets.UTF_8)
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val posix = "posix" in dir.fileSystem.supportedFileAttributeViews()
        try {
            val channel: SeekableByteChannel = if (posix) {
                Files.newByteChannel(path, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                Files.newByteChannel(path, options)
            }
            channel.use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) ch.write(buf)
            }
        } catch (e: FileAlreadyExistsException) {
            throw IllegalArgumentException("config already exists at $path; not overwriting", e)
        }
        return Result(path, config, token)
    }

    fun mcpUrl(config: SvodConfig): String = "http://${config.host}:${config.mcpPort}/mcp"

    /** The Claude Desktop / Cursor `mcpServers` entry. */
    fun mcpServersJson(config: SvodConfig, token: String): String = prettyJson.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject(SERVER_NAME) {
                    put("url", mcpUrl(config))
                    putJsonObject("headers") { put("Authorization", "Bearer $token") }
                }
            }
        },
    )

    /** Everything a new user pastes to connect an agent: client config, CLI line, system prompt. */
    fun clientSetup(config: SvodConfig, token: String): String = buildString {
        val url = mcpUrl(config)
        appendLine("Claude Desktop / Cursor — add to the MCP config:")
        appendLine()
        appendLine(mcpServersJson(config, token))
        appendLine()
        appendLine("Claude Code:")
        appendLine()
        appendLine("  claude mcp add --transport http $SERVER_NAME $url --header \"Authorization: Bearer $token\"")
        appendLine()
        appendLine("System prompt for your agent:")
        appendLine()
        appendLine(SYSTEM_PROMPT)
    }

    /** Word for word the README's "Ready-to-paste system prompt". */
    val SYSTEM_PROMPT: String = """
        You have a persistent, git-backed memory called Svod, reached through its MCP tools. It is your
        durable long-term memory across sessions — auditable Markdown notes, not a black box.

        Recall first, then answer:
        - Before answering anything that might rely on earlier knowledge, call `context_pack` with a short
          query and a token budget (e.g. 1500) to pull the relevant notes assembled into one cited block.
          Use `search` (mode=hybrid) when you want raw hits to inspect, or filter-only (tags/pathPrefix) to
          browse. Use `link` / `graph_query` to follow connections between notes.

        Write durable facts as you learn them:
        - Use `write` to save a note as Markdown. Put it under a clear path (e.g. `projects/<x>/decisions.md`).
          Add YAML frontmatter `tags: [..]` for retrieval, and link related notes with `[[wikilinks]]`.
        - To UPDATE an existing note safely, first `read` it to get its `revision`, then `write` with
          `expectedRevision` set to that value. If you get a `conflict`, someone changed it meanwhile — re-read,
          merge, and retry. Never blindly overwrite.
        - Draft uncertain material under `messy/` and `promote` it into `vault/` once confirmed.
        - Deletes are soft (recoverable); use `delete` rather than overwriting with empty content.

        Principles:
        - Prefer small, well-titled, linked notes over one giant note. One fact per note where it helps recall.
        - Everything you write is attributed to you and versioned — write as if a human will read the diff.
        - If a tool returns `rate_limited` or `denied`, stop and report it; do not retry in a loop.
    """.trimIndent()
}
