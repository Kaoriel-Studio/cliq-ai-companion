package com.cliq.plugin.history

import com.cliq.plugin.settings.CliqSettings
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CliqPromptHistoryTest : BasePlatformTestCase() {

    private fun history() = project.service<CliqPromptHistory>()

    override fun setUp() {
        super.setUp()
        CliqSettings.getInstance().promptHistoryEnabled = true
        history().clear()
    }

    override fun tearDown() {
        try {
            history().clear()
            CliqSettings.getInstance().promptHistoryEnabled = true
        } finally {
            super.tearDown()
        }
    }

    fun testRecordsNewestFirst() {
        history().record("first")
        history().record("second")

        val entries = history().entries()
        assertEquals(2, entries.size)
        assertEquals("second", entries[0].text)
        assertEquals("first", entries[1].text)
    }

    fun testDeduplicatesRepeatedPrompt() {
        history().record("same")
        history().record("other")
        history().record("same")

        val entries = history().entries()
        assertEquals(2, entries.size)
        assertEquals("same", entries[0].text)
        assertEquals("other", entries[1].text)
    }

    fun testIgnoresBlankPrompts() {
        history().record("   ")
        history().record("\n\t")

        assertEmpty(history().entries())
    }

    fun testTrimsStoredText() {
        history().record("  padded  ")
        assertEquals("padded", history().entries()[0].text)
    }

    fun testCapsNumberOfEntries() {
        repeat(CliqPromptHistory.MAX_ENTRIES + 15) { history().record("prompt $it") }

        assertEquals(CliqPromptHistory.MAX_ENTRIES, history().entries().size)
    }

    fun testStoresLongPromptsInFull() {
        val long = "x".repeat(CliqPromptHistory.MAX_ENTRY_LENGTH)
        history().record(long)

        assertEquals(long, history().entries()[0].text)
    }

    fun testTruncatesPromptsOverTheLimitAndMarksThem() {
        history().record("x".repeat(CliqPromptHistory.MAX_ENTRY_LENGTH + 1))

        val stored = history().entries().single().text
        assertEquals(CliqPromptHistory.MAX_ENTRY_LENGTH + CliqPromptHistory.TRUNCATION_MARKER.length, stored.length)
        assertTrue(stored.endsWith(CliqPromptHistory.TRUNCATION_MARKER))
    }

    fun testDropsLegacyTruncatedEntriesOnLoad() {
        val state = CliqPromptHistory.State().apply {
            entries = mutableListOf(
                PromptHistoryEntry("cut off… [truncated]", 2),
                PromptHistoryEntry("complete", 1),
            )
        }
        history().loadState(state)

        assertEquals(listOf("complete"), history().entries().map { it.text })
    }

    fun testRespectsDisabledSetting() {
        CliqSettings.getInstance().promptHistoryEnabled = false
        history().record("should not be stored")

        assertEmpty(history().entries())
    }

    fun testGetStateReturnsDetachedCopy() {
        history().record("original")

        val state = history().getState()
        state.entries.clear()

        assertEquals(1, history().entries().size)
    }

    fun testEntriesAreDetachedFromInternalState() {
        history().record("original")

        history().entries()[0].text = "mutated"

        assertEquals("original", history().entries()[0].text)
    }
}
