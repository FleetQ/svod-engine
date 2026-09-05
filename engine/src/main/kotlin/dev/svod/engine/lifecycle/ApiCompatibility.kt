package dev.svod.engine.lifecycle

/**
 * API-compatibility gate for self-update. The engine and its clients (UI, examples) are
 * released independently against the versioned App API contract, so an update may only be
 * applied if it stays compatible with the contract clients already speak.
 *
 * Rule (semver on the App API contract version):
 *  - same MAJOR ⇒ compatible (minor/patch are additive/backward-compatible),
 *  - MAJOR bump ⇒ incompatible (breaking; needs a coordinated client migration),
 *  - downgrade ⇒ refused.
 */
object ApiCompatibility {

    /** The App API contract version this engine implements (matches contract/openapi.yaml). */
    const val CURRENT_CONTRACT_VERSION = "0.30.0"

    data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int =
            compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

        override fun toString() = "$major.$minor.$patch"

        companion object {
            fun parse(v: String): SemVer {
                val core = v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
                val parts = core.split('.')
                require(parts.size in 1..3 && parts.all { it.toIntOrNull() != null }) { "invalid semver: '$v'" }
                return SemVer(parts[0].toInt(), parts.getOrElse(1) { "0" }.toInt(), parts.getOrElse(2) { "0" }.toInt())
            }
        }
    }

    sealed interface Result {
        data object Compatible : Result
        data class Incompatible(val reason: String) : Result
    }

    /** May an engine implementing [candidate]'s contract replace one implementing [running]? */
    fun check(running: String, candidate: String): Result {
        val cur = SemVer.parse(running)
        val next = SemVer.parse(candidate)
        return when {
            next < cur -> Result.Incompatible("downgrade refused: $candidate < $running")
            next.major != cur.major -> Result.Incompatible("major version change $running → $candidate breaks the API contract")
            else -> Result.Compatible
        }
    }

    fun isCompatible(running: String, candidate: String): Boolean = check(running, candidate) is Result.Compatible
}

/**
 * Gates a self-update on API compatibility. The actual artifact download + binary swap is a
 * `dist/` responsibility (launchd-managed); this is the safety check that must pass first.
 */
class SelfUpdate(private val runningContractVersion: String = ApiCompatibility.CURRENT_CONTRACT_VERSION) {

    /** Decide whether an update advertising [candidateContractVersion] may be applied. */
    fun preflight(candidateContractVersion: String): ApiCompatibility.Result =
        ApiCompatibility.check(runningContractVersion, candidateContractVersion)
}
