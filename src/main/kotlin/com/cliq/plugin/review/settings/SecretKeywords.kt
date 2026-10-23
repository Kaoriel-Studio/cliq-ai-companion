package com.cliq.plugin.review.settings

object SecretKeywords {

    const val MAX_KEYWORDS = 50
    private val VALID = Regex("^[A-Za-z0-9_.\\-]{2,40}$")

    fun normalize(raw: String): List<String> =
        raw.split('\n', ',', ';')
            .map(String::trim)
            .filter { VALID.matches(it) }
            .distinctBy { it.lowercase() }
            .take(MAX_KEYWORDS)

    fun invalidEntries(raw: String): List<String> =
        raw.split('\n', ',', ';').map(String::trim).filter { it.isNotEmpty() && !VALID.matches(it) }
}
