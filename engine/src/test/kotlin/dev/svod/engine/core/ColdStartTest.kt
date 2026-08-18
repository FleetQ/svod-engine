package dev.svod.engine.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val CARL = Author("carl", "carl@svod.test")

/**
 * Opening a vault must stay cheap without narrowing what recovery covers.
 *
 * `recover()` used to run [GitRepo.commitAll] unconditionally, whose jgit add/status stat every
 * tracked file — measured at **15.3 s** of a ~52 s cold boot on the operator's 3,370-note vault, paid
 * on every start to handle the rare interrupted write. It is now gated on a native
 * `git status --porcelain`, measured at **20 ms** on the same vault.
 *
 * The risk of that change is a NARROWED recovery, so these tests are about what must still happen,
 * not about speed. `CrashRecoveryTest` covers the crash path; this covers the boot path.
 *
 * NB (`mem:kotlin-junit-silent-skip`): every body is a block body, so JUnit collects them.
 */
class ColdStartTest {

    @Test
    fun `a committed tree reads as definitely clean, an untracked file does not`() {
        VaultFixture.create().use { fx ->
            val engine = fx.open()
            runBlocking { engine.write("a.md", "one", expectedRevision = null, author = CARL) }
            assertTrue(GitCli.isWorkingTreeClean(fx.root), "fixture precondition")

            val repo = GitRepo.openOrInit(fx.root)
            assertTrue(repo.isDefinitelyClean(), "a fully committed vault is definitely clean")

            Files.writeString(fx.root.resolve("stray.md"), "written behind the engine's back")
            // The load-bearing direction: if an untracked file could read as "clean", recovery would
            // skip it and the file would never be committed.
            assertTrue(
                !repo.isDefinitelyClean(),
                "an untracked file must NOT read as definitely clean",
            )
        }
    }

    @Test
    fun `a file written while the engine was down is still committed at the next open`() {
        VaultFixture.create().use { fx ->
            val first = fx.open()
            runBlocking { first.write("a.md", "one", expectedRevision = null, author = CARL) }
            val before = GitCli.commitCount(fx.root)
            fx.simulateCrash()

            // Exactly the case the fast path must not swallow: an external edit made while nothing
            // was running. `git status` sees it, so the skip does not apply and recovery commits it.
            Files.writeString(fx.root.resolve("offline.md"), "edited in another editor while Svod was off")
            assertEquals(false, GitCli.isWorkingTreeClean(fx.root), "precondition: the edit is uncommitted")

            val reopened = fx.open()

            assertTrue(GitCli.isWorkingTreeClean(fx.root), "recovery must commit the offline edit")
            assertEquals(before + 1, GitCli.commitCount(fx.root), "exactly one recovery commit")
            val read = runBlocking { reopened.read("offline.md") }
            assertNotNull(read, "the offline edit must not be lost")
            assertEquals("edited in another editor while Svod was off", read.text)
        }
    }

    @Test
    fun `opening an already-clean vault adds no commit`() {
        VaultFixture.create().use { fx ->
            val first = fx.open()
            runBlocking { first.write("a.md", "one", expectedRevision = null, author = CARL) }
            val before = GitCli.commitCount(fx.root)
            fx.simulateCrash()

            fx.open()

            assertEquals(before, GitCli.commitCount(fx.root), "nothing to recover ⇒ nothing to commit")
            assertTrue(GitCli.isWorkingTreeClean(fx.root))
        }
    }
}
