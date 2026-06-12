package dev.svod.engine.security

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
}
