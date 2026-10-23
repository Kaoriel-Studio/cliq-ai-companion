package com.cliq.plugin.review.collect.budget

object TokenEstimator {

    private const val ASCII_CHARS_PER_TOKEN = 4
    private const val INDENT_CHARS_PER_TOKEN = 8
    private const val ALPHABETIC_CHARS_PER_TOKEN_NUMERATOR = 5
    private const val ALPHABETIC_CHARS_PER_TOKEN_DENOMINATOR = 2
    private const val SAFETY_MARGIN_PERCENT = 10

    private val DENSE_SCRIPTS = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.THAI,
    )

    fun estimate(text: CharSequence): Int = withMargin(estimateRaw(text))

    fun withMargin(rawTokens: Int): Int = rawTokens + rawTokens * SAFETY_MARGIN_PERCENT / 100

    fun estimateRaw(text: CharSequence): Int {
        var tokens = 0
        var index = 0
        var atLineStart = true
        val length = text.length

        while (index < length) {
            val c = text[index]
            when {
                c == '\n' -> {
                    tokens++
                    index++
                    atLineStart = true
                    continue
                }
                isHorizontalSpace(c) -> {
                    val start = index
                    while (index < length && isHorizontalSpace(text[index])) index++
                    if (atLineStart) tokens += ceilDiv(index - start, INDENT_CHARS_PER_TOKEN)
                    continue
                }
                isAsciiWordChar(c) -> {
                    val start = index
                    while (index < length && isAsciiWordChar(text[index])) index++
                    tokens += ceilDiv(index - start, ASCII_CHARS_PER_TOKEN)
                }
                isDense(c) -> {
                    while (index < length && isDense(text[index])) {
                        tokens++
                        index++
                    }
                }
                Character.isLetterOrDigit(c) -> {
                    val start = index
                    while (index < length && isAlphabetic(text[index])) index++
                    val runLength = index - start
                    tokens += ceilDiv(runLength * ALPHABETIC_CHARS_PER_TOKEN_DENOMINATOR, ALPHABETIC_CHARS_PER_TOKEN_NUMERATOR)
                }
                Character.isHighSurrogate(c) -> {
                    tokens += 2
                    index += 2
                }
                else -> {
                    tokens++
                    index++
                }
            }
            atLineStart = false
        }
        return tokens
    }

    fun lineCount(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        var lines = 1
        text.forEach { if (it == '\n') lines++ }
        return if (text.last() == '\n') lines - 1 else lines
    }

    private fun isHorizontalSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r'

    private fun isAsciiWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    private fun isAlphabetic(c: Char): Boolean = c.code > 127 && Character.isLetterOrDigit(c) && !isDense(c)

    private fun isDense(c: Char): Boolean =
        c.code > 127 && !Character.isSurrogate(c) && Character.UnicodeScript.of(c.code) in DENSE_SCRIPTS

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}
