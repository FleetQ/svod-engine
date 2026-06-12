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
        content.lineSequence().forEachIndexed { i, line ->
            for (rule in RULES) {
                if (rule.pattern.containsMatchIn(line)) findings += Finding(rule.name, i + 1)
            }
        }
        return findings
    }

    private data class Rule(val name: String, val pattern: Regex)

    companion object {
        /** Disabled scanner (the engine default). */
        val OFF = SecretScanner(false)

        private val RULES = listOf(
            Rule("private-key", Regex("-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----")),
            Rule("aws-access-key-id", Regex("\\bAKIA[0-9A-Z]{16}\\b")),
            Rule("github-token", Regex("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b")),
            Rule("slack-token", Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b")),
            Rule("google-api-key", Regex("\\bAIza[0-9A-Za-z_\\-]{35}\\b")),
            Rule("jwt", Regex("\\beyJ[A-Za-z0-9_\\-]{8,}\\.eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\b")),
            Rule("private-key-assignment", Regex("(?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token)\"?\\s*[:=]\\s*\"[A-Za-z0-9_\\-]{24,}\"")),
        )
    }
}
