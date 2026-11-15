package dev.svod.engine.teamsharing

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** The core scenario scaled up. Opt-in: `-Dsvod.perf=true` (the build forwards it). */
class TeamSharingHeavyTest {

    @Test
    fun `heavy - 500 notes per agent and sustained contention on one shared note`(): Unit = runBlocking {
        assumeTrue(System.getProperty("svod.perf") == "true") { "heavy run is opt-in; pass -Dsvod.perf=true" }
        TeamFixture().use { fx ->
            val report = runConcurrentScenario(fx, notesPerAgent = 500, sharedEditsPerAgent = 100)
            println("TEAM-SHARING heavy: $report")
            writeReport("heavy", report)
            assertTrue(report.conflicts > 0)
        }
    }
}
