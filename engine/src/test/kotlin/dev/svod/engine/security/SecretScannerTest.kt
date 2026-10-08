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
        assertTrue(scanner.scan(BEGIN_RSA + "\nMIIabc\n" + END_RSA).any { it.rule == "private-key" })
        assertTrue(scanner.scan(jwt).any { it.rule == "jwt" })
        assertTrue(scanner.scan(assign).any { it.rule == "private-key-assignment" })
    }

    @Test
    fun `prose and normal markdown are clean`() {
        assertEquals(emptyList(), scanner.scan("# Notes\nThe secret to good tea is patience and water just off the boil."))
        assertEquals(emptyList(), scanner.scan("My password manager keeps tokens; I love writing about API design."))
    }

    // ---- private-key: a real key, not the bare header line (08.10.2026: a session note quoting the
    //      header in a code review sat quarantined for 4 days) ----

    @Test
    fun `the private-key header alone in prose is not a key`() {
        // What the quarantined session note held: the header quoted as a string, on two lines, no body, no END.
        val review = "Redaction check: the scrubber must catch\n" + BEGIN + "\nbefore the note is written.\n" +
            "Also the inline form `" + BEGIN + "` and " + BEGIN_RSA + " blocks are covered.\n"
        assertEquals(emptyList(), scanner.scan(review).filter { it.rule == "private-key" })
        assertEquals(emptyList(), scanner.scan(BEGIN).filter { it.rule == "private-key" })
        assertEquals(emptyList(), scanner.scan(BEGIN + "\n" + END).filter { it.rule == "private-key" })
        assertEquals(emptyList(), scanner.scan(BEGIN + " and " + END + " lines").filter { it.rule == "private-key" })
    }

    @Test
    fun `a real PEM private key is caught at its BEGIN line`() {
        val pem = pem("PRIVATE KEY", realKeyDer())
        val note = "# Server\nsome text\n$pem\nmore text\n"
        assertEquals(listOf(SecretScanner.Finding("private-key", 3)), scanner.scan(note).filter { it.rule == "private-key" })
    }

    @Test
    fun `a key body without its END line is still caught`() {
        val truncated = pem("RSA PRIVATE KEY", realKeyDer()).lines().take(4).joinToString("\n")
        assertTrue(scanner.scan("pasted:\n$truncated\n").any { it.rule == "private-key" && it.line == 2 })
    }

    @Test
    fun `a short BEGIN-END block is caught`() {
        assertTrue(scanner.scan(BEGIN + "\nMIIabc\n" + END).any { it.rule == "private-key" })
        assertTrue(scanner.scan(BEGIN + "MIIabcDEFghiJKLmnoPQR" + END).any { it.rule == "private-key" })
    }

    @Test
    fun `an encrypted PEM with headers and an OpenSSH key are caught`() {
        val body = pem("RSA PRIVATE KEY", realKeyDer()).lines()
        val encrypted = (listOf(body.first(), "Proc-Type: 4,ENCRYPTED", "DEK-Info: AES-128-CBC,0123456789ABCDEF", "") + body.drop(1)).joinToString("\n")
        assertTrue(scanner.scan(encrypted).any { it.rule == "private-key" && it.line == 1 })
        val openssh = pem("OPENSSH PRIVATE KEY", realKeyDer(), width = 70)
        assertTrue(scanner.scan(openssh).any { it.rule == "private-key" })
    }

    @Test
    fun `a key inlined with escaped newlines (service-account JSON) is caught`() {
        val b64 = java.util.Base64.getEncoder().encodeToString(realKeyDer())
        val json = "{\"private_key\": \"" + BEGIN + "\\n" + b64.chunked(64).joinToString("\\n") + "\\n" + END + "\\n\"}"
        assertEquals(1, json.lines().size)
        assertTrue(scanner.scan(json).any { it.rule == "private-key" && it.line == 1 })
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

    private companion object {
        // Built at runtime: no literal key header or key material is committed.
        const val DASHES = "-----"
        val BEGIN = DASHES + "BEGIN " + "PRIVATE KEY" + DASHES
        val END = DASHES + "END " + "PRIVATE KEY" + DASHES
        val BEGIN_RSA = DASHES + "BEGIN RSA " + "PRIVATE KEY" + DASHES
        val END_RSA = DASHES + "END RSA " + "PRIVATE KEY" + DASHES

        fun realKeyDer(): ByteArray =
            java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private.encoded

        fun pem(kind: String, der: ByteArray, width: Int = 64): String {
            val b64 = java.util.Base64.getEncoder().encodeToString(der)
            return (listOf(DASHES + "BEGIN " + kind + DASHES) + b64.chunked(width) + (DASHES + "END " + kind + DASHES)).joinToString("\n")
        }
    }
}
