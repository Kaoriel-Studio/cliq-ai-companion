package com.cliq.plugin.templates

object PromptTemplateVariables {

    const val ACTIVE_FILE = "activeFile"
    const val SELECTION = "selection"
    const val CLIPBOARD = "clipboard"

    private val PLACEHOLDER_PATTERN = Regex("\\{\\{\\s*(\\w+)\\s*}}")

    val SUPPORTED: Set<String> = setOf(ACTIVE_FILE, SELECTION, CLIPBOARD)

    fun placeholders(content: String): Set<String> =
        PLACEHOLDER_PATTERN.findAll(content)
            .map { it.groupValues[1] }
            .filter { it in SUPPORTED }
            .toSet()

    fun uses(content: String, placeholder: String): Boolean =
        placeholders(content).contains(placeholder)

    fun resolve(content: String, activeFile: String?, selection: String?, clipboard: String?): String =
        PLACEHOLDER_PATTERN.replace(content) { match ->
            when (match.groupValues[1]) {
                ACTIVE_FILE -> activeFile.orEmpty()
                SELECTION -> selection.orEmpty()
                CLIPBOARD -> clipboard.orEmpty()
                else -> match.value
            }
        }

    fun unresolved(content: String, activeFile: String?, selection: String?, clipboard: String?): Set<String> {
        val used = placeholders(content)
        val missing = LinkedHashSet<String>()
        if (ACTIVE_FILE in used && activeFile.isNullOrEmpty()) missing.add(ACTIVE_FILE)
        if (SELECTION in used && selection.isNullOrEmpty()) missing.add(SELECTION)
        if (CLIPBOARD in used && clipboard.isNullOrEmpty()) missing.add(CLIPBOARD)
        return missing
    }
}
