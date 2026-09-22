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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UpdateService(
    private val currentAppVersion: String,
    private val currentContract: String = ApiCompatibility.CURRENT_CONTRACT_VERSION,
    // Resolved on every apply, so a script installed while the engine runs is picked up without a restart.
    private val selfUpdateScript: () -> String? = { resolveScript() },
    private val logFile: Path = DEFAULT_LOG,
    private val releaseFetcher: suspend () -> FetchResult,
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

    /** [release] is null on failure, with [error] describing why (shown to the user as `notes`). */
    data class FetchResult(val release: ReleaseInfo?, val error: String? = null)

    override suspend fun check(): UpdateCheckDto {
        val result = releaseFetcher()
        val latest = result.release ?: return UpdateCheckDto(
            currentVersion = currentAppVersion,
            currentContract = currentContract,
            notes = result.error ?: "could not reach the update server",
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
        private const val REPO = "FleetQ/svod-engine"
        private val CONFIG_DIR: Path = Paths.get(System.getProperty("user.home"), ".config", "svod")
        val DEFAULT_SCRIPT: Path = CONFIG_DIR.resolve("self-update.sh")
        val DEFAULT_LOG: Path = CONFIG_DIR.resolve("self-update.log")

        /** `SVOD_SELF_UPDATE_SCRIPT` if set, else [defaultScript] when that file exists. */
        fun resolveScript(env: Map<String, String> = System.getenv(), defaultScript: Path = DEFAULT_SCRIPT): String? =
            env["SVOD_SELF_UPDATE_SCRIPT"]?.takeIf { it.isNotBlank() }
                ?: defaultScript.takeIf { Files.isRegularFile(it) }?.toString()

        // GitHub's anonymous api.github.com limit is 60 requests/hour per IP, shared by every tool
        // on the same NAT/office/VPN. A redirect from github.com/<repo>/releases/latest (not
        // api.github.com) names the current tag without spending any of that budget, and the
        // release's own SHA256SUMS asset gives the checksum the same way. The API is only a
        // fallback: for a tag lookup the redirect didn't resolve, or a release published before
        // SHA256SUMS existed.
        fun productionFetcher(): suspend () -> FetchResult = {
            runCatching { fetchLatestRelease() }.getOrElse { FetchResult(null, "could not reach the update server") }
        }

        private fun fetchLatestRelease(): FetchResult {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            val hostLabel = currentHostLabel()
            val assetName = "SvodEngine-$hostLabel.tar.gz"

            val tag = resolveLatestTag(client)
                ?: return fetchFromApi(client, "https://api.github.com/repos/$REPO/releases/latest", hostLabel)

            val sha256 = fetchSha256FromSums(client, tag, assetName)
                ?: return fetchFromApi(client, "https://api.github.com/repos/$REPO/releases/tags/$tag", hostLabel)

            return FetchResult(
                ReleaseInfo(
                    tag = tag,
                    appVersion = tag.removePrefix("v"),
                    notes = null,
                    publishedAt = null,
                    assetName = assetName,
                    assetUrl = "https://github.com/$REPO/releases/download/$tag/$assetName",
                    sha256 = sha256,
                ),
            )
        }

        /** The tag named by the redirect `github.com/<repo>/releases/latest` sends, or null. */
        private fun resolveLatestTag(client: HttpClient): String? = runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://github.com/$REPO/releases/latest"))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "svod-engine")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in 300..399) return null
            parseLatestTagFromRedirect(response.headers().firstValue("Location").orElse(null))
        }.getOrNull()

        /** The release's own checksum asset, or null if it doesn't have one (older releases). */
        private fun fetchSha256FromSums(client: HttpClient, tag: String, assetName: String): String? = runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://github.com/$REPO/releases/download/$tag/SHA256SUMS"))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "svod-engine")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            parseSha256Sums(response.body(), assetName)
        }.getOrNull()

        private fun fetchFromApi(client: HttpClient, url: String, hostLabel: String): FetchResult {
            val token = System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN")
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "svod-engine")
                .header("Accept", "application/vnd.github+json")
            token?.let { builder.header("Authorization", "Bearer $it") }
            val response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
            return when {
                response.statusCode() == 403 ->
                    FetchResult(null, rateLimitMessage(response.headers().firstValue("X-RateLimit-Reset").orElse(null)))
                response.statusCode() != 200 ->
                    FetchResult(null, "could not reach the update server (HTTP ${response.statusCode()} from $url)")
                else ->
                    parseRelease(response.body(), hostLabel)?.let { FetchResult(it) }
                        ?: FetchResult(null, "could not reach the update server")
            }
        }

        /** The tag from a `Location: https://github.com/<repo>/releases/tag/<tag>` header, or null. */
        internal fun parseLatestTagFromRedirect(location: String?): String? =
            location?.let { Regex("/releases/tag/([^/?#]+)").find(it)?.groupValues?.get(1) }

        /** The hex digest for [assetName] in a standard `sha256sum` `SHA256SUMS` file, or null. */
        internal fun parseSha256Sums(text: String, assetName: String): String? =
            text.lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2 && parts[1].trimStart('*') == assetName) parts[0] else null
                }
                .firstOrNull()

        /** api.github.com's anonymous limit is 60 requests/hour per IP; the reset header is a unix timestamp. */
        internal fun rateLimitMessage(resetEpochSeconds: String?): String {
            val resetAt = resetEpochSeconds?.toLongOrNull()?.let {
                Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz"))
            }
            return if (resetAt != null) {
                "GitHub's anonymous API rate limit (60 requests/hour per IP) is used up; it resets at $resetAt. " +
                    "Set GITHUB_TOKEN to raise the limit, or try again after that time."
            } else {
                "GitHub's anonymous API rate limit is used up; set GITHUB_TOKEN to raise it, or try again later."
            }
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
