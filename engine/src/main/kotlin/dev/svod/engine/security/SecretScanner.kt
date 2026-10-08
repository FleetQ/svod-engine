package dev.svod.engine.security

/**
 * Scans note content for leaked secrets BEFORE it is committed, so a credential never enters
 * git history (where it would be permanent). Rules are deliberately **high-confidence** —
 * structurally unmistakable tokens — to keep a knowledge base of prose free of false alarms.
 *
 * Off by default; the lifecycle layer enables it via config.
 */
class SecretScanner(private val enabled: Boolean) {

    data class Finding(val rule: String, val line: Int)

    fun scan(content: String): List<Finding> {
        if (!enabled) return emptyList()
        val findings = mutableListOf<Finding>()
        val lines = content.lines()
        lines.forEachIndexed { i, line ->
            if (isPrivateKey(lines, i)) findings += Finding("private-key", i + 1)
            for (rule in RULES) {
                if (rule.pattern.containsMatchIn(line)) findings += Finding(rule.name, i + 1)
            }
        }
        return findings
    }

    /**
     * A private key, not just its header: notes that discuss redaction quote the BEGIN line on its
     * own, and that alone must not block a write or quarantine a synced file. Counts as a key when
     * the BEGIN line at [i] is followed by key material — on the same line (inline PEM, or JSON with
     * escaped `\n`), or on the next lines as base64 that runs into the END line or spans at least two
     * full-width lines (a paste cut before its END). PEM headers (`Proc-Type:`, `DEK-Info:`) and the
     * blank line after them are skipped.
     */
    private fun isPrivateKey(lines: List<String>, i: Int): Boolean {
        val header = PEM_BEGIN.find(lines[i]) ?: return false
        val rest = lines[i].substring(header.range.last + 1)
        if (rest.isNotBlank()) {
            val inline = INLINE_BODY.find(rest) ?: return false
            return inline.groupValues[1].replace("\\n", "").length >= 16
        }
        var j = i + 1
        while (j < lines.size && j <= i + 3 && (PEM_HEADER.matches(lines[j].trim()) || lines[j].isBlank())) j++
        val body = generateSequence(j) { it + 1 }.takeWhile { it < lines.size && BASE64_LINE.matches(lines[it].trim()) }.toList()
        if (body.isEmpty()) return false
        val after = body.last() + 1
        if (after < lines.size && PEM_END.containsMatchIn(lines[after])) return true
        return body.size >= 2 && lines[body.first()].trim().length >= 40
    }

    private data class Rule(val name: String, val pattern: Regex)

    companion object {
        /** Disabled scanner (the engine default). */
        val OFF = SecretScanner(false)

        private const val KEY_KINDS = "(?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY"
        private val PEM_BEGIN = Regex("-----BEGIN $KEY_KINDS-----")
        private val PEM_END = Regex("-----END $KEY_KINDS-----")
        private val PEM_HEADER = Regex("[A-Za-z][A-Za-z0-9-]*: .*")
        private val BASE64_LINE = Regex("[A-Za-z0-9+/]+={0,2}")
        /** Key material right after the header on one line: base64 (with literal `\n` escapes) up to the END marker or the line's end. */
        private val INLINE_BODY = Regex("^(?:\\\\n|\\s)*([A-Za-z0-9+/=]+(?:\\\\n[A-Za-z0-9+/=]+)*)(?:\\\\n|\\s)*(?:-----END $KEY_KINDS-----|\"|$)")

        private val RULES = listOf(
            Rule("aws-access-key-id", Regex("\\bAKIA[0-9A-Z]{16}\\b")),
            Rule("github-token", Regex("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b")),
            Rule("slack-token", Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b")),
            Rule("google-api-key", Regex("\\bAIza[0-9A-Za-z_\\-]{35}\\b")),
            Rule("jwt", Regex("\\beyJ[A-Za-z0-9_\\-]{8,}\\.eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\b")),
            Rule("private-key-assignment", Regex("(?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token)\"?\\s*[:=]\\s*\"[A-Za-z0-9_\\-]{24,}\"")),
        )
    }
}
