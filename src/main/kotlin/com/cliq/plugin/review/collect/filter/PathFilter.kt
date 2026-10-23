package com.cliq.plugin.review.collect.filter

import com.cliq.plugin.review.git.GitRefNames

enum class PathVerdict(val reason: String) {
    UNSAFE("unsafe_path"),
    SENSITIVE("sensitive"),
    EXCLUDED("excluded_pattern"),
}

class PathPatternSet(patterns: Collection<String>) {

    private val matchers: List<Regex> = patterns.mapNotNull(::compile)

    fun matches(path: String): Boolean = matchers.any { it.containsMatchIn(path) }

    companion object {
        fun parseLines(text: String): List<String> =
            text.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()

        private fun compile(rawPattern: String): Regex? {
            val pattern = rawPattern.trim().trimStart('/').replace('\\', '/')
            if (pattern.isEmpty()) return null
            val directoryPrefix = listOf("/**", "/*").firstOrNull { pattern.endsWith(it) }
            val source = if (directoryPrefix != null) {
                "(^|/)" + globToRegex(pattern.removeSuffix(directoryPrefix)) + "/"
            } else {
                "(^|/)" + globToRegex(pattern) + "$"
            }
            return Regex(source, RegexOption.IGNORE_CASE)
        }

        private fun globToRegex(glob: String): String = buildString {
            var index = 0
            while (index < glob.length) {
                val c = glob[index]
                when {
                    c == '*' && glob.getOrNull(index + 1) == '*' -> {
                        append(".*")
                        index++
                    }
                    c == '*' -> append("[^/]*")
                    c == '?' -> append("[^/]")
                    else -> append(Regex.escape(c.toString()))
                }
                index++
            }
        }
    }
}

object SensitivePaths {

    val PATTERNS: List<String> = listOf(
        ".env",
        ".env.*",
        "*.env",
        "*.key",
        "*.pem",
        "*.crt",
        "*.cer",
        "*.der",
        "*.p12",
        "*.pfx",
        "*.jks",
        "*.keystore",
        "*.kdbx",
        "*.asc",
        "*.gpg",
        "*.ovpn",
        "*.tfstate",
        "*.tfstate.*",
        "*.tfvars",
        "id_rsa",
        "id_dsa",
        "id_ecdsa",
        "id_ed25519",
        "secrets.*",
        "*.secrets",
        "credentials",
        "credentials.json",
        "credentials.yml",
        "credentials.yaml",
        "service-account*.json",
        ".npmrc",
        ".pypirc",
        ".netrc",
        "_netrc",
        ".git-credentials",
        ".htpasswd",
        ".dockercfg",
        ".docker/config.json",
        "config/secrets*",
        "config/master.key",
        "config/credentials/*",
        "storage/*.key",
        ".ssh/*",
        ".aws/*",
        ".gnupg/*",
    )

    private val matcher = PathPatternSet(PATTERNS)

    fun matches(path: String): Boolean = matcher.matches(path)
}

class PathFilter(userPatterns: Collection<String>) {

    private val excluded = PathPatternSet(userPatterns)

    fun verdict(path: String): PathVerdict? = when {
        !GitRefNames.isSafeRepoPath(path) -> PathVerdict.UNSAFE
        SensitivePaths.matches(path) -> PathVerdict.SENSITIVE
        excluded.matches(path) -> PathVerdict.EXCLUDED
        else -> null
    }

    fun allows(path: String): Boolean = verdict(path) == null
}
