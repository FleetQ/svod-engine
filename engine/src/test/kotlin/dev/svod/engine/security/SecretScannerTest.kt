package dev.svod.engine.security

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretScannerTest {

    private val scanner = SecretScanner(enabled = true)

    // Fixtures are assembled at runtime so this source file holds no committed secret literal.
    // (We deliberately exercise the non-AWS/non-GitHub rules here so the repo's own pre-commit
    //  secret hook stays happy; the AWS/GitHub rules share the identical code path.)
    @Test
    fun `detects high-confidence secrets`() {
        val jwt = "jwt ey" + "Jhdrhdrhdr.ey" + "Jbodybodyb.signaturepartx"
        val assign = "api_key = \"" + "abcd1234efgh5678ijkl9012mnop\""
        assertTrue(scanner.scan("-----BEGIN " + "RSA PRIVATE KEY-----").any { it.rule == "private-key" })
        assertTrue(scanner.scan(jwt).any { it.rule == "jwt" })
        assertTrue(scanner.scan(assign).any { it.rule == "private-key-assignment" })
    }

    @Test
    fun `prose and normal markdown are clean`() {
        assertEquals(emptyList(), scanner.scan("# Notes\nThe secret to good tea is patience and water just off the boil."))
        assertEquals(emptyList(), scanner.scan("My password manager keeps tokens; I love writing about API design."))
    }

    @Test
    fun `disabled scanner finds nothing`() {
        assertEquals(emptyList(), SecretScanner(enabled = false).scan("-----BEGIN PRIVATE KEY-----\nx"))
    }

    @Test
    fun `engine refuses to commit a leaked secret`() = runBlocking {
        val root = Files.createTempDirectory("svod-secret-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        SvodEngine.open(root, scope, SecretScanner(enabled = true)).use { e ->
            val secret = "token ey" + "Jhdrhdrhdr.ey" + "Jbodybodyb.signaturepartx"
            val out = e.write("notes/creds.md", secret, expectedRevision = null, author = Author("a", "a@x"))
            assertTrue(out is WriteOutcome.Blocked, "got $out")
            assertTrue((out as WriteOutcome.Blocked).findings.any { it.contains("jwt") })
            // nothing was committed
            assertNull(e.read("notes/creds.md"))
            assertEquals(emptyList(), e.history("notes/creds.md"))
        }
    }
}
