package com.cliq.plugin.review.collect.filter

import java.util.concurrent.atomic.AtomicInteger

class SecretRedactor(
    knownSecrets: Collection<String> = emptyList(),
    customKeywords: Collection<String> = emptyList(),
) {

    private class Rule(val kind: String, val regex: Regex, val keepGroup: Int? = null)

    private companion object {
        const val MIN_KNOWN_SECRET_LENGTH = 8

        val RULES = listOf(
            Rule(
                "private-key",
                Regex("-----BEGIN ((?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?)-----[\\s\\S]*?-----END \\1-----"),
            ),
            Rule("aws-access-key", Regex("\\b(?:AKIA|ASIA|AGPA|AIDA|AROA)[0-9A-Z]{16}\\b")),
            Rule(
                "aws-secret-key",
                Regex("(?i)(aws_?secret_?access_?key[\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9/+=]{40}"),
                keepGroup = 1,
            ),
            Rule("gitlab-token", Regex("\\bgl[a-z]{2,6}-[0-9A-Za-z_\\-]{20,}")),
            Rule("gitlab-runner-token", Regex("\\bGR1348941[0-9A-Za-z_\\-]{20,}")),
            Rule("github-token", Regex("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}\\b")),
            Rule("github-token", Regex("\\bgithub_pat_[A-Za-z0-9_]{60,}\\b")),
            Rule("slack-token", Regex("\\bxox[abposr]-[A-Za-z0-9\\-]{10,}")),
            Rule("slack-webhook", Regex("https://hooks\\.slack\\.com/services/[A-Za-z0-9/_\\-]+")),
            Rule("google-api-key", Regex("\\bAIza[0-9A-Za-z_\\-]{35}\\b")),
            Rule("stripe-key", Regex("\\b(?:sk|rk)_(?:live|test)_[0-9A-Za-z]{16,}\\b")),
            Rule("llm-api-key", Regex("\\bsk-(?:ant-|proj-)?[A-Za-z0-9_\\-]{32,}")),
            Rule("npm-token", Regex("\\bnpm_[A-Za-z0-9]{36}\\b")),
            Rule(
                "jwt",
                Regex("\\beyJ[A-Za-z0-9_\\-]{10,}\\.eyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}"),
            ),
            Rule(
                "url-credentials",
                Regex("(?i)\\b([a-z][a-z0-9+.\\-]*://)[^/\\s:@\"'<>]+:[^/\\s@\"'<>]+@"),
                keepGroup = 1,
            ),
            Rule(
                "secret-assignment",
                Regex(
                    "(?i)(\\b[A-Za-z0-9_.\\-]{0,40}(?:password|passwd|pwd|secret|api[_\\-]?key|access[_\\-]?token|" +
                        "auth[_\\-]?token|client[_\\-]?secret|private[_\\-]?key)[A-Za-z0-9_.\\-]{0,40}[\"']?\\s{0,8}[:=]>?\\s{0,8})" +
                        "([\"'])[^\"'\\s\$%{<][^\"'\\s]{7,511}\\2",
                ),
                keepGroup = 1,
            ),
        )
    }

    private val known: List<String> = knownSecrets
        .filter { it.length >= MIN_KNOWN_SECRET_LENGTH }
        .distinct()
        .sortedByDescending { it.length }

    private val rules: List<Rule> = RULES + customRules(customKeywords)

    private val redactions = AtomicInteger()

    val redactionCount: Int get() = redactions.get()

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        known.forEach { secret ->
            if (result.contains(secret)) {
                redactions.addAndGet(countOccurrences(result, secret))
                result = result.replace(secret, marker("known-secret"))
            }
        }
        rules.forEach { rule ->
            result = rule.regex.replace(result) { match ->
                redactions.incrementAndGet()
                val kept = rule.keepGroup?.let { match.groupValues[it] }.orEmpty()
                kept + marker(rule.kind)
            }
        }
        return result
    }

    private fun customRules(keywords: Collection<String>): List<Rule> {
        val alternation = keywords.filter { it.isNotBlank() }.joinToString("|") { Regex.escape(it) }
        if (alternation.isEmpty()) return emptyList()
        val name = "\\b[A-Za-z0-9_.\\-]{0,40}(?:$alternation)[A-Za-z0-9_.\\-]{0,40}"
        return listOf(
            Rule(
                "custom-secret",
                Regex("(?i)($name[\"']?\\s{0,8}[:=]>?\\s{0,8})([\"'])[^\"'\\s\$%{<][^\"'\\s]{2,511}\\2"),
                keepGroup = 1,
            ),
            Rule(
                "custom-secret",
                Regex("(?i)($name\\s{0,8}[:=]\\s{0,8})[^\\s\"'`,;()\\[\\]{}\$%<][^\\s\"'`,;()\\[\\]{}]{3,511}+(?![(\\w.])"),
                keepGroup = 1,
            ),
        )
    }

    private fun marker(kind: String): String = "[REDACTED:$kind]"

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }
}
