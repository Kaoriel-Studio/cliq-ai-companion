package com.cliq.plugin.review.collect.render

object XmlText {

    private const val REPLACEMENT = 0xFFFD
    private const val CDATA_END = "]]>"
    private const val CDATA_SPLIT = "]]]]><![CDATA[>"

    fun sanitize(value: String): String {
        if (value.all(::isAllowedBmpChar)) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            out.appendCodePoint(if (isAllowed(codePoint)) codePoint else REPLACEMENT)
            index += Character.charCount(codePoint)
        }
        return out.toString()
    }

    fun text(value: String): String = escape(sanitize(value), attribute = false)

    fun attribute(value: String): String = escape(sanitize(value), attribute = true)

    fun cdata(value: String): String = "<![CDATA[" + sanitize(value).replace(CDATA_END, CDATA_SPLIT) + "]]>"

    private fun isAllowedBmpChar(c: Char): Boolean =
        c == '\t' || c == '\n' || c == '\r' || c in ' '..'퟿' || c in ''..'�'

    private fun isAllowed(codePoint: Int): Boolean =
        codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
            codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF

    private fun escape(value: String, attribute: Boolean): String {
        val out = StringBuilder(value.length + 16)
        value.forEach { c ->
            when {
                c == '&' -> out.append("&amp;")
                c == '<' -> out.append("&lt;")
                c == '>' -> out.append("&gt;")
                attribute && c == '"' -> out.append("&quot;")
                attribute && c == '\'' -> out.append("&apos;")
                attribute && c == '\n' -> out.append("&#10;")
                attribute && c == '\r' -> out.append("&#13;")
                attribute && c == '\t' -> out.append("&#9;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
