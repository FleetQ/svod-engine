package dev.svod.engine.lifecycle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Where a running engine can be found. The app defaults to 127.0.0.1:7517 and launchd label
 * `dev.svod.engine`, but an install may use other ports or another label — and then the app only
 * showed "offline" with nothing to go on. On every start the daemon writes its actual ports and
 * label here; the app reads this file when its configured endpoint doesn't answer.
 *
 * No secrets: loopback address, ports, pid and the launchd label only.
 */
object EngineDiscovery {

    val DEFAULT_PATH: Path = Paths.get(System.getProperty("user.home"), ".config", "svod", "engine.json")

    /** launchd exports the job label as XPC_SERVICE_NAME; "0" or absent means not under launchd. */
    fun launchdLabel(env: Map<String, String> = System.getenv()): String? =
        env["XPC_SERVICE_NAME"]?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

    fun write(
        path: Path,
        host: String,
        appApiPort: Int,
        mcpPort: Int,
        pid: Long = ProcessHandle.current().pid(),
        launchdLabel: String? = launchdLabel(),
    ) {
        val json = buildJsonObject {
            put("host", host)
            put("appApiPort", appApiPort)
            put("mcpPort", mcpPort)
            put("pid", pid)
            if (launchdLabel != null) put("launchdLabel", launchdLabel)
        }
        path.parent?.let { Files.createDirectories(it) }
        // Write-then-rename so the app never reads a half-written file.
        val tmp = Files.createTempFile(path.parent ?: Paths.get("."), "engine", ".json.tmp")
        try {
            Files.writeString(tmp, Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), json) + "\n")
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
