package com.cliq.plugin.review.collect

object JobLogCleaner {

    private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[@-Z\\\\-_]")
    private val SECTION_MARKER = Regex("section_(?:start|end):\\d+:[A-Za-z0-9_.\\-\\[\\]=,]*")
    private val LEADING_TIMESTAMP = Regex("^\\uFEFF?\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z ")

    fun clean(raw: String, maxLines: Int): String {
        val withoutEscapes = ANSI_ESCAPE.replace(raw, "")
        return withoutEscapes
            .split('\n')
            .map { line -> line.trimEnd('\r').substringAfterLast('\r') }
            .map { line -> SECTION_MARKER.replace(LEADING_TIMESTAMP.replace(line, ""), "").trimEnd() }
            .filter { it.isNotBlank() }
            .map { line -> line.filter { !it.isISOControl() || it == '\t' } }
            .takeLast(maxLines)
            .joinToString("\n")
    }
}
