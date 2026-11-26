package dev.svod.engine.security

/**
 * Finds personal data in note content so an agent's write that adds some can wait for a person's
 * review (`personalDataScanning`). A review aid, not a block: nothing is refused or redacted.
 *
 * Rules are conservative so prose does not trip them: an email address, a `+`-prefixed international
 * phone number, an IBAN that passes its mod-97 check and a card number that passes the Luhn check.
 * Matched values stay inside [Finding] (normalised, `internal`) and are never printed by it: they
 * only serve the "is this personal data new?" comparison.
 *
 * Off by default; the lifecycle layer enables it via config.
 */
class PersonalDataScanner(val enabled: Boolean) {

    data class Finding(val rule: String, val line: Int, internal val value: String) {
        /** `rule (line N)`: what a tool result or audit entry may show; never the value. */
        fun report(): String = "$rule (line $line)"
        override fun toString(): String = "Finding(rule=$rule, line=$line)"
    }

    fun scan(content: String): List<Finding> {
        if (!enabled) return emptyList()
        val findings = mutableListOf<Finding>()
        content.lines().forEachIndexed { i, line ->
            val n = i + 1
            EMAIL.findAll(line).forEach { findings += Finding("email", n, it.value.lowercase()) }
            PHONE.findAll(line).forEach { findings += Finding("phone", n, it.value.filter(Char::isDigit)) }
            IBAN.findAll(line).forEach { m ->
                prefixes(m.value) { it == ' ' }.map { it.filterNot(Char::isWhitespace) }
                    .firstOrNull { it.length in 15..34 && ibanValid(it) }
                    ?.let { findings += Finding("iban", n, it) }
            }
            CARD.findAll(line).forEach { m ->
                prefixes(m.value) { it == ' ' || it == '-' }.map { it.filter(Char::isDigit) }
                    .firstOrNull { it.length in 13..19 && luhnValid(it) }
                    ?.let { findings += Finding("payment-card", n, it) }
            }
        }
        return findings
    }

    /**
     * [match] and each prefix of it that ends before a separator, longest first. The regexes are greedy,
     * so a following group (`EUR`, a CVV, a year) can be swallowed into the match and fail the check.
     */
    private fun prefixes(match: String, isSeparator: (Char) -> Boolean): Sequence<String> = sequence {
        yield(match)
        for (i in match.indices.reversed()) if (isSeparator(match[i])) yield(match.substring(0, i))
    }

    companion object {
        /** Disabled scanner (the default). */
        val OFF = PersonalDataScanner(false)

        private val EMAIL = Regex("(?i)(?<![A-Z0-9._%+-])[A-Z0-9][A-Z0-9._%+-]*@[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?)*\\.[A-Z]{2,}\\b")
        /** `+` then 8–15 digits, optionally split by single spaces, dots or dashes. */
        private val PHONE = Regex("(?<![\\w+])\\+\\d(?:[ .-]?\\d){7,14}(?![\\d])")
        /** Two letters, two check digits, then 11–30 alphanumerics, plain or in space-separated groups of 4. */
        private val IBAN = Regex("\\b[A-Z]{2}\\d{2}(?:[A-Z0-9]{11,30}|(?: [A-Z0-9]{4}){2,7}(?: [A-Z0-9]{1,4})?)\\b")
        /**
         * 13–19 digits starting with 2–6: unbroken, in groups of 4 split by spaces or dashes, or Amex 4-6-5.
         * A separator is not allowed between any two digits, or date ranges and number lists would match.
         */
        private val CARD = Regex(
            "(?<![\\w+.-])(?:[2-6]\\d{12,18}|[2-6]\\d{3}(?:[ -]\\d{4}){2,3}(?:[ -]\\d{1,4})?|3[47]\\d{2}[ -]\\d{6}[ -]\\d{5})(?![\\d])",
        )

        internal fun ibanValid(iban: String): Boolean {
            val rearranged = iban.substring(4) + iban.substring(0, 4)
            var rem = 0
            for (c in rearranged) {
                val v = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'A'..'Z' -> c - 'A' + 10
                    else -> return false
                }
                rem = if (v >= 10) (rem * 100 + v) % 97 else (rem * 10 + v) % 97
            }
            return rem == 1
        }

        internal fun luhnValid(digits: String): Boolean {
            var sum = 0
            digits.reversed().forEachIndexed { i, c ->
                var d = c - '0'
                if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
                sum += d
            }
            return sum % 10 == 0
        }
    }
}
