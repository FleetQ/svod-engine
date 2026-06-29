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
import java.time.Duration

class UpdateService(
    private val currentAppVersion: String,
    private val currentContract: String = ApiCompatibility.CURRENT_CONTRACT_VERSION,
    private val selfUpdateScript: String? = System.getenv("SVOD_SELF_UPDATE_SCRIPT"),
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
        val script = selfUpdateScript ?: throw UpdateAdmin.NotSupported("no self-update script configured")
        ProcessBuilder("/usr/bin/env", "bash", script, c.latestVersion ?: "", c.assetUrl ?: "", c.sha256 ?: "")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        return UpdateApplyDto(started = true, candidateVersion = c.latestVersion)
    }

    companion object {
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

        private fun parseRelease(json: String): ReleaseInfo? = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val appVersion = tag.removePrefix("v")
            val notes = root["body"]?.jsonPrimitive?.contentOrNull
            val publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull

            val os = System.getProperty("os.name").lowercase()
            val hostLabel = when {
                os.contains("mac") -> "macos-arm64"
                os.contains("win") -> "windows-x64"
                else -> "linux-x64"
            }

            var assetName: String? = null
            var assetUrl: String? = null
            var sha256: String? = null

            val assets = root["assets"]?.jsonArray
            if (assets != null) {
                for (asset in assets) {
                    val obj = asset.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (name.contains(hostLabel)) {
                        assetName = name
                        assetUrl = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull
                        sha256 = obj["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")
                        break
                    }
                }
            }

            ReleaseInfo(tag, appVersion, notes, publishedAt, assetName, assetUrl, sha256)
        }.getOrNull()
    }
}
