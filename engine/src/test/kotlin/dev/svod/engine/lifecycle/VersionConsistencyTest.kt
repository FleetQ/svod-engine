package dev.svod.engine.lifecycle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The engine's version lives in two places: the Gradle `version` (which names the jar and the
 * release tag) and the `currentAppVersion` literal `UpdateService` reports to clients. They have
 * silently drifted apart twice — v1.8.0 shipped with the constant left at 1.7.0, v1.11.2 with it
 * left at 1.11.1 — and each time the engine advertised an update to a version it was already
 * running, which no client could ever clear. Nothing caught it, because nothing compared them.
 */
class VersionConsistencyTest {

    @Test
    fun `SvodNode currentAppVersion matches the Gradle version`() {
        val gradleVersion = System.getProperty("svod.projectVersion")
        assertNotNull(gradleVersion, "svod.projectVersion was not forwarded by the test task")

        val source = File(projectDir, "src/main/kotlin/dev/svod/engine/lifecycle/SvodNode.kt")
        assertTrue(source.isFile, "SvodNode.kt not found at ${source.path}")

        val declared = Regex("currentAppVersion\\s*=\\s*\"([^\"]+)\"")
            .find(source.readText())
            ?.groupValues?.get(1)
        assertNotNull(declared, "no currentAppVersion literal found in SvodNode.kt")

        assertEquals(
            gradleVersion,
            declared,
            "engine version drift: Gradle version is $gradleVersion but " +
                "SvodNode.currentAppVersion is $declared. Cutting a release means bumping BOTH " +
                "engine/build.gradle.kts and SvodNode.kt.",
        )
    }

    private val projectDir: File
        get() = File(System.getProperty("svod.projectDir") ?: ".")
}
