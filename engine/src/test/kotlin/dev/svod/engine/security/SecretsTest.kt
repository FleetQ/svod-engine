package dev.svod.engine.security

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecretsTest {

    @Test
    fun `literal passes through`() {
        assertEquals("plain-token", Secrets.resolve("plain-token"))
    }

    @Test
    fun `file ref reads and trims file contents`() {
        val f = Files.createTempFile("secret", ".txt")
        Files.writeString(f, "  s3cr3t-value\n")
        assertEquals("s3cr3t-value", Secrets.resolve("file:$f"))
    }

    @Test
    fun `missing env ref fails loudly`() {
        assertFailsWith<IllegalStateException> { Secrets.resolve("env:SVOD_DEFINITELY_UNSET_VAR_XYZ") }
    }

    @Test
    fun `present env ref resolves`() {
        // PATH is always set in the test JVM environment
        assertEquals(System.getenv("PATH"), Secrets.resolve("env:PATH"))
    }

    @Test
    fun `keychain ref round-trips on macOS`() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"), "keychain is macOS-only")
        val service = "svod-test-${ProcessHandle.current().pid()}"
        val account = "token"
        fun security(vararg args: String) = ProcessBuilder(listOf("security") + args).start().waitFor()
        // -A allows access without a GUI prompt (test-only)
        assertEquals(0, security("add-generic-password", "-s", service, "-a", account, "-w", "k3ychain-value", "-A", "-U"))
        try {
            assertEquals("k3ychain-value", Secrets.resolve("keychain:$service/$account"))
        } finally {
            security("delete-generic-password", "-s", service, "-a", account)
        }
    }
}
