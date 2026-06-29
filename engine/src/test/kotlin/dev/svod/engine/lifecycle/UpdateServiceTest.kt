package dev.svod.engine.lifecycle

import dev.svod.engine.api.UpdateAdmin
import kotlinx.coroutines.runBlocking
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
        selfUpdateScript = script,
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
}
