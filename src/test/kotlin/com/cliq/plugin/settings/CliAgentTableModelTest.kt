package com.cliq.plugin.settings

import com.cliq.plugin.agents.CliAgentDefinition
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class CliAgentTableModelTest {

    private fun agent(name: String) = CliAgentDefinition(
        displayName = name,
        executablePath = name.lowercase(),
    )

    @Test
    fun replaceAllWithOwnBackingListKeepsRows() {
        val backing = mutableListOf(agent("Claude"), agent("Gemini"), agent("Qwen"))
        val model = CliAgentTableModel(backing)

        model.replaceAll(backing)

        assertEquals(3, model.rowCount)
        assertEquals("Claude", model.getValueAt(0, 0))
        assertEquals("Qwen", model.getValueAt(2, 0))
    }

    @Test
    fun replaceAllWithSnapshotOfItselfKeepsRows() {
        val backing = mutableListOf(agent("Claude"), agent("Gemini"))
        val model = CliAgentTableModel(backing)

        model.replaceAll(model.snapshot())

        assertEquals(2, model.rowCount)
    }

    @Test
    fun replaceAllDetachesFromSourceList() {
        val backing = mutableListOf(agent("Claude"))
        val model = CliAgentTableModel(backing)
        val source = mutableListOf(agent("Gemini"))

        model.replaceAll(source)
        source.clear()

        assertEquals(1, model.rowCount)
        assertEquals("Gemini", model.getValueAt(0, 0))
    }

    @Test
    fun snapshotReturnsDetachedCopies() {
        val backing = mutableListOf(agent("Claude"))
        val model = CliAgentTableModel(backing)

        val snapshot = model.snapshot()
        assertNotSame(backing[0], snapshot[0])

        snapshot[0].displayName = "Mutated"
        assertEquals("Claude", model.getValueAt(0, 0))
    }

    @Test
    fun replaceAllUpdatesRowCountForShorterList() {
        val backing = mutableListOf(agent("A"), agent("B"), agent("C"))
        val model = CliAgentTableModel(backing)

        model.replaceAll(listOf(agent("Only")))

        assertEquals(1, model.rowCount)
        assertTrue(backing.size == 1)
    }
}
