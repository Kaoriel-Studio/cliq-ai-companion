package com.cliq.plugin.templates

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptTemplateVariablesTest {

    @Test
    fun resolvesKnownPlaceholders() {
        val resolved = PromptTemplateVariables.resolve(
            "File {{activeFile}} selection {{selection}} clip {{clipboard}}",
            activeFile = "src/Main.kt",
            selection = "fun main()",
            clipboard = "pasted",
        )
        assertEquals("File src/Main.kt selection fun main() clip pasted", resolved)
    }

    @Test
    fun toleratesWhitespaceInsidePlaceholder() {
        val resolved = PromptTemplateVariables.resolve("{{  selection  }}", null, "code", null)
        assertEquals("code", resolved)
    }

    @Test
    fun leavesUnknownPlaceholderUntouched() {
        val resolved = PromptTemplateVariables.resolve("{{unknown}}", "a", "b", "c")
        assertEquals("{{unknown}}", resolved)
    }

    @Test
    fun missingValuesBecomeEmptyStrings() {
        val resolved = PromptTemplateVariables.resolve("[{{selection}}]", null, null, null)
        assertEquals("[]", resolved)
    }

    @Test
    fun doesNotRecursivelyExpandSubstitutedText() {
        val resolved = PromptTemplateVariables.resolve("{{clipboard}}", null, "SECRET", "{{selection}}")
        assertEquals("{{selection}}", resolved)
    }

    @Test
    fun replacementIsLiteralAndIgnoresGroupSyntax() {
        val resolved = PromptTemplateVariables.resolve("{{clipboard}}", null, null, "\$1 and \\n")
        assertEquals("\$1 and \\n", resolved)
    }

    @Test
    fun detectsUsedPlaceholders() {
        assertTrue(PromptTemplateVariables.uses("a {{clipboard}} b", PromptTemplateVariables.CLIPBOARD))
        assertFalse(PromptTemplateVariables.uses("a {{selection}} b", PromptTemplateVariables.CLIPBOARD))
        assertEquals(
            setOf(PromptTemplateVariables.SELECTION, PromptTemplateVariables.ACTIVE_FILE),
            PromptTemplateVariables.placeholders("{{selection}} {{activeFile}} {{nope}}"),
        )
    }

    @Test
    fun reportsUnresolvedPlaceholders() {
        val missing = PromptTemplateVariables.unresolved(
            "{{selection}} {{activeFile}}",
            activeFile = "src/Main.kt",
            selection = null,
            clipboard = null,
        )
        assertEquals(setOf(PromptTemplateVariables.SELECTION), missing)
    }

    @Test
    fun reportsNothingWhenPlaceholderUnused() {
        val missing = PromptTemplateVariables.unresolved("plain text", null, null, null)
        assertTrue(missing.isEmpty())
    }
}
