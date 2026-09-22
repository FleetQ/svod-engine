package dev.svod.engine.lifecycle

import dev.svod.engine.api.UpdateAdmin
import dev.svod.engine.api.UpdateApplyDto
import dev.svod.engine.api.UpdateCheckDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

class UpdateService(
    private val currentAppVersion: String,
    private val currentContract: String = ApiCompatibility.CURRENT_CONTRACT_VERSION,
    // Resolved on every apply, so a script installed while the engine runs is picked up without a restart.
    private val selfUpdateScript: () -> String? = { resolveScript() },
    private val logFile: Path = DEFAULT_LOG,
    private val releaseFetcher: suspend () -> ReleaseInfo?,
) : UpdateAdmin {

    data class ReleaseInfo(
        val tag: String,
        val appVersion: String,
        val notes: String?,
        val publishedAt: String?,
        val assetName: String?,
        val assetUrl: String?,
        val sha256: String?,
    )

    override suspend fun check(): UpdateCheckDto {
        val latest = releaseFetcher() ?: return UpdateCheckDto(
            currentVersion = currentAppVersion,
            currentContract = currentContract,
            notes = "could not reach the update server",
            updateAvailable = false,
            compatible = false,
        )
        val curSV = ApiCompatibility.SemVer.parse(currentAppVersion)
        val latestSV = ApiCompatibility.SemVer.parse(latest.appVersion)
        return UpdateCheckDto(
            currentVersion = currentAppVersion,
            currentContract = currentContract,
            latestVersion = latest.appVersion,
            updateAvailable = latestSV > curSV,
            compatible = latestSV.major == curSV.major,
            assetName = latest.assetName,
            assetUrl = latest.assetUrl,
            sha256 = latest.sha256,
            notes = latest.notes,
            publishedAt = latest.publishedAt,
        )
    }

    override suspend fun apply(): UpdateApplyDto {
        val c = check()
        if (!c.updateAvailable || !c.compatible) throw UpdateAdmin.NotApplicable("no compatible update available")
        val script = selfUpdateScript() ?: throw UpdateAdmin.NotSupported(
            "no self-update script: install self-update.sh from the release at $DEFAULT_SCRIPT, or set SVOD_SELF_UPDATE_SCRIPT",
        )
        // The script's output used to be discarded, so a failed update left no trace at all.
        logFile.parent?.let { Files.createDirectories(it) }
        ProcessBuilder("/usr/bin/env", "bash", script, c.latestVersion ?: "", c.assetUrl ?: "", c.sha256 ?: "")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
            .start()
        return UpdateApplyDto(started = true, candidateVersion = c.latestVersion)
    }

    companion object {
        private val CONFIG_DIR: Path = Paths.get(System.getProperty("user.home"), ".config", "svod")
        val DEFAULT_SCRIPT: Path = CONFIG_DIR.resolve("self-update.sh")
        val DEFAULT_LOG: Path = CONFIG_DIR.resolve("self-update.log")

        /** `SVOD_SELF_UPDATE_SCRIPT` if set, else [defaultScript] when that file exists. */
        fun resolveScript(env: Map<String, String> = System.getenv(), defaultScript: Path = DEFAULT_SCRIPT): String? =
            env["SVOD_SELF_UPDATE_SCRIPT"]?.takeIf { it.isNotBlank() }
                ?: defaultScript.takeIf { Files.isRegularFile(it) }?.toString()

        fun productionFetcher(): suspend () -> ReleaseInfo? = {
            runCatching {
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/FleetQ/svod-engine/releases/latest"))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "svod-engine")
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) null
                else parseRelease(response.body())
            }.getOrNull()
        }

        internal fun parseRelease(json: String, hostLabel: String = currentHostLabel()): ReleaseInfo? = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val appVersion = tag.removePrefix("v")
            val notes = root["body"]?.jsonPrimitive?.contentOrNull
            val publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull

            // Prefer the app-image archive: it is the asset the release requires and the one the
            // self-update script can install into every layout. The native binary used to win only
            // because it is listed first, and the script could not unpack it.
            val assets = root["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            fun nameOf(o: kotlinx.serialization.json.JsonObject) = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val archives = setOf("SvodEngine-$hostLabel.tar.gz", "SvodEngine-$hostLabel.zip")
            val asset = assets.firstOrNull { nameOf(it) in archives }
                ?: assets.firstOrNull { nameOf(it).contains(hostLabel) }

            ReleaseInfo(
                tag, appVersion, notes, publishedAt,
                assetName = asset?.let { nameOf(it) },
                assetUrl = asset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
                sha256 = asset?.get("digest")?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:"),
            )
        }.getOrNull()

        private fun currentHostLabel(): String {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") -> "macos-arm64"
                os.contains("win") -> "windows-x64"
                else -> "linux-x64"
            }
        }
    }
}
