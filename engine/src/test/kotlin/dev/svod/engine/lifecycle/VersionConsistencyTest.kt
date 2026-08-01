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

    /**
     * The CONTRACT version had the same shape of problem: `ApiCompatibility` gated self-update on it
     * while `AppApiServer.Config` advertised its own hardcoded copy to `/settings`, and
     * `contract/openapi.yaml` carried a third. Bumping the contract for v1.12.0 moved one and left
     * the other two, so the engine gated on 0.23.0 while telling the macOS app it spoke 0.22.0.
     * All three are now compared here; `Config` derives its value, so this pins the remaining pair.
     */
    @Test
    fun `the App API contract version is the same everywhere it is published`() {
        val gate = ApiCompatibility.CURRENT_CONTRACT_VERSION
        val advertised = dev.svod.engine.api.AppApiServer.Config().apiVersion
        assertEquals(
            gate, advertised,
            "contract drift: the self-update gate says $gate but /settings advertises $advertised",
        )

        val spec = File(projectDir.parentFile, "contract/openapi.yaml")
        assertTrue(spec.isFile, "openapi.yaml not found at ${spec.path}")
        val specVersion = Regex("(?m)^\\s{2}version:\\s*\"?([0-9]+\\.[0-9]+\\.[0-9]+)\"?\\s*$")
            .find(spec.readText())
            ?.groupValues?.get(1)
        assertNotNull(specVersion, "no top-level `version:` found in contract/openapi.yaml")
        assertEquals(
            specVersion, gate,
            "contract drift: contract/openapi.yaml declares $specVersion but " +
                "ApiCompatibility.CURRENT_CONTRACT_VERSION is $gate. Bumping the contract means " +
                "bumping BOTH.",
        )
    }

    private val projectDir: File
        get() = File(System.getProperty("svod.projectDir") ?: ".")
}
