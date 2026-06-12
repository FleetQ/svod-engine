package dev.svod.engine.lifecycle

import kotlin.test.Test
import kotlin.test.assertTrue

class ApiCompatibilityTest {

    @Test
    fun `same major is compatible, major bump and downgrade are not`() {
        assertTrue(ApiCompatibility.isCompatible("0.1.0", "0.1.0"))
        assertTrue(ApiCompatibility.isCompatible("0.1.0", "0.2.0"), "additive minor is compatible")
        assertTrue(ApiCompatibility.isCompatible("0.1.0", "0.1.5"), "patch is compatible")

        assertTrue(!ApiCompatibility.isCompatible("0.1.0", "1.0.0"), "major bump breaks the contract")
        assertTrue(!ApiCompatibility.isCompatible("1.2.0", "2.0.0"))
        assertTrue(!ApiCompatibility.isCompatible("0.2.0", "0.1.0"), "downgrade refused")
    }

    @Test
    fun `preflight reports a reason on refusal`() {
        val result = SelfUpdate("0.1.0").preflight("1.0.0")
        assertTrue(result is ApiCompatibility.Result.Incompatible)
        assertTrue((result as ApiCompatibility.Result.Incompatible).reason.contains("major"))
    }

    @Test
    fun `parses v-prefixed and pre-release versions`() {
        assertTrue(ApiCompatibility.isCompatible("v0.1.0", "0.1.1-rc.1"))
    }
}
