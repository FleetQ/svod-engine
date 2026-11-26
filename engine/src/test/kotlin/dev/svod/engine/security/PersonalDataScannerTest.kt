package dev.svod.engine.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonalDataScannerTest {

    private val scanner = PersonalDataScanner(true)

    private fun rules(text: String) = scanner.scan(text).map { it.rule to it.line }

    @Test
    fun `finds an email with its line`() {
        assertEquals(listOf("email" to 2), rules("# Contact\nWrite to Jane.Doe@example.org about it."))
    }

    @Test
    fun `finds an international phone number`() {
        assertEquals(listOf("phone" to 1), rules("Call +44 20 7946 0958 after nine."))
        assertEquals(listOf("phone" to 1), rules("Call +44.20.7946.0958"))
        assertEquals(listOf("phone" to 1), rules("Call +442079460958"))
    }

    @Test
    fun `finds a valid IBAN with and without spaces`() {
        assertEquals(listOf("iban" to 1), rules("Pay to GB82 WEST 1234 5698 7654 32 please"))
        assertEquals(listOf("iban" to 3), rules("a\nb\nPay to GB82WEST12345698765432."))
    }

    @Test
    fun `finds a Luhn-valid card number with spaces or dashes`() {
        assertEquals(listOf("payment-card" to 1), rules("Card 4111 1111 1111 1111 on file"))
        assertEquals(listOf("payment-card" to 1), rules("Card 4111-1111-1111-1111 on file"))
        assertEquals(listOf("payment-card" to 1), rules("Card 4111111111111111"))
    }

    @Test
    fun `ignores numbers that fail their check and plain prose`() {
        assertTrue(rules("Card 4111 1111 1111 1112").isEmpty(), "Luhn fails")
        assertTrue(rules("IBAN GB83 WEST 1234 5698 7654 32").isEmpty(), "mod-97 fails")
        assertTrue(rules("Call 020 7946 0958 locally; version 1.35.0; 2026-11-26; ticket #4411").isEmpty())
        assertTrue(rules("Use @mentions and a@b without a domain suffix").isEmpty())
    }

    @Test
    fun `values are normalised for comparison but never printed`() {
        val f = scanner.scan("Jane.Doe@Example.org, GB82 WEST 1234 5698 7654 32, 4111-1111-1111-1111, +44 20 7946 0958")
        assertEquals(setOf("email", "iban", "payment-card", "phone"), f.map { it.rule }.toSet())
        assertEquals("jane.doe@example.org", f.single { it.rule == "email" }.value)
        assertEquals("GB82WEST12345698765432", f.single { it.rule == "iban" }.value)
        assertEquals("4111111111111111", f.single { it.rule == "payment-card" }.value)
        assertEquals("442079460958", f.single { it.rule == "phone" }.value)
        f.forEach { finding ->
            assertTrue(finding.value !in finding.toString(), "toString leaks the value: $finding")
            assertEquals("${finding.rule} (line 1)", finding.report())
        }
    }

    @Test
    fun `disabled scanner finds nothing`() {
        assertTrue(PersonalDataScanner.OFF.scan("Jane.Doe@example.org +44 20 7946 0958").isEmpty())
        assertTrue(PersonalDataScanner(false).scan("4111 1111 1111 1111").isEmpty())
    }
}
