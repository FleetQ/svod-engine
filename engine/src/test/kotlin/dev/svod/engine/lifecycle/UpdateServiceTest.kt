package dev.svod.engine.lifecycle

import dev.svod.engine.api.UpdateAdmin
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateServiceTest {

    private fun release(version: String) = UpdateService.ReleaseInfo(
        tag = "v$version",
        appVersion = version,
        notes = "Release $version",
        publishedAt = "2026-01-01T00:00:00Z",
        assetName = "svod-engine-$version-macos-arm64.tar.gz",
        assetUrl = "https://example.com/svod-engine-$version.tar.gz",
        sha256 = "abc123",
    )

    private fun service(
        current: String,
        fetcher: suspend () -> UpdateService.ReleaseInfo?,
        script: String? = null,
    ) = UpdateService(
        currentAppVersion = current,
        releaseFetcher = fetcher,
        selfUpdateScript = { script },
        logFile = Files.createTempDirectory("svod-update").resolve("self-update.log"),
    )

    @Test
    fun `newer same-major release is updateAvailable and compatible`() = runBlocking {
        val svc = service("1.7.0", { release("1.8.0") })
        val result = svc.check()
        assertTrue(result.updateAvailable)
        assertTrue(result.compatible)
        assertEquals("1.7.0", result.currentVersion)
        assertEquals("1.8.0", result.latestVersion)
    }

    @Test
    fun `same version is not updateAvailable`() = runBlocking {
        val svc = service("1.7.0", { release("1.7.0") })
        val result = svc.check()
        assertFalse(result.updateAvailable)
    }

    @Test
    fun `major version bump is updateAvailable but not compatible`() = runBlocking {
        val svc = service("1.7.0", { release("2.0.0") })
        val result = svc.check()
        assertTrue(result.updateAvailable)
        assertFalse(result.compatible)
    }

    @Test
    fun `fetcher returning null gives no update without throwing`() = runBlocking {
        val svc = service("1.7.0", { null })
        val result = svc.check()
        assertFalse(result.updateAvailable)
        assertNotNull(result.notes)
        assertNull(result.latestVersion)
    }

    @Test
    fun `apply throws NotApplicable when no update is available`() = runBlocking {
        val svc = service("1.7.0", { release("1.7.0") }, script = "/tmp/update.sh")
        assertFailsWith<UpdateAdmin.NotApplicable> { svc.apply() }
        Unit
    }

    @Test
    fun `apply throws NotSupported when update is available but no script configured`() = runBlocking {
        val svc = service("1.7.0", { release("1.8.0") }, script = null)
        assertFailsWith<UpdateAdmin.NotSupported> { svc.apply() }
        Unit
    }

    @Test
    fun `apply without a script names where to install one`() = runBlocking {
        val svc = service("1.7.0", { release("1.8.0") }, script = null)
        val e = assertFailsWith<UpdateAdmin.NotSupported> { svc.apply() }
        assertTrue(e.message!!.contains(UpdateService.DEFAULT_SCRIPT.toString()), e.message)
    }

    @Test
    fun `apply runs the script and appends its output to the log`() = runBlocking {
        val dir = Files.createTempDirectory("svod-update")
        val script = dir.resolve("update.sh")
        Files.writeString(script, "echo \"args: \$1 \$2 \$3\"\n")
        val log = dir.resolve("logs/self-update.log")
        val svc = UpdateService(
            currentAppVersion = "1.7.0",
            selfUpdateScript = { script.toString() },
            logFile = log,
            releaseFetcher = { release("1.8.0") },
        )
        val r = svc.apply()
        assertTrue(r.started)
        val deadline = System.currentTimeMillis() + 10_000
        while ((!Files.exists(log) || Files.readString(log).isBlank()) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("args: 1.8.0 https://example.com/svod-engine-1.8.0.tar.gz abc123", Files.readString(log).trim())
    }

    @Test
    fun `script resolution prefers the env var, then the default file`() {
        val dir = Files.createTempDirectory("svod-update")
        val default = dir.resolve("self-update.sh")
        assertNull(UpdateService.resolveScript(emptyMap(), default))
        Files.writeString(default, "#!/bin/bash\n")
        assertEquals(default.toString(), UpdateService.resolveScript(emptyMap(), default))
        assertEquals("/opt/up.sh", UpdateService.resolveScript(mapOf("SVOD_SELF_UPDATE_SCRIPT" to "/opt/up.sh"), default))
        assertEquals(default.toString(), UpdateService.resolveScript(mapOf("SVOD_SELF_UPDATE_SCRIPT" to " "), default))
    }

    @Test
    fun `release parsing picks the app-image archive even when the native binary is listed first`() {
        val json = """
            {"tag_name":"v1.25.1","body":"notes","published_at":"2026-09-22T00:00:00Z","assets":[
              {"name":"svod-engine-linux-x64","browser_download_url":"https://x/svod-engine-linux-x64","digest":"sha256:l1"},
              {"name":"svod-engine-macos-arm64","browser_download_url":"https://x/svod-engine-macos-arm64","digest":"sha256:n1"},
              {"name":"SvodEngine-linux-x64.tar.gz","browser_download_url":"https://x/SvodEngine-linux-x64.tar.gz","digest":"sha256:l2"},
              {"name":"SvodEngine-macos-arm64.tar.gz","browser_download_url":"https://x/SvodEngine-macos-arm64.tar.gz","digest":"sha256:a1"}
            ]}
        """.trimIndent()
        val r = assertNotNull(UpdateService.parseRelease(json, "macos-arm64"))
        assertEquals("1.25.1", r.appVersion)
        assertEquals("SvodEngine-macos-arm64.tar.gz", r.assetName)
        assertEquals("https://x/SvodEngine-macos-arm64.tar.gz", r.assetUrl)
        assertEquals("a1", r.sha256)
    }

    @Test
    fun `release parsing falls back to the native binary when no archive is attached`() {
        val json = """{"tag_name":"v1.25.1","assets":[{"name":"svod-engine-macos-arm64","browser_download_url":"https://x/b","digest":"sha256:n1"}]}"""
        val r = assertNotNull(UpdateService.parseRelease(json, "macos-arm64"))
        assertEquals("svod-engine-macos-arm64", r.assetName)
    }
}
