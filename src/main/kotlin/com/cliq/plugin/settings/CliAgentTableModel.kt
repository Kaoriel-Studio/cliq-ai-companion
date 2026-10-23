package com.cliq.plugin.settings

import com.cliq.plugin.agents.CliAgentDefinition
import javax.swing.table.AbstractTableModel

class CliAgentTableModel(private val agents: MutableList<CliAgentDefinition>) : AbstractTableModel() {

    private val columns = listOf("Display Name", "Executable", "Arguments", "Working Directory")

    override fun getRowCount(): Int = agents.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val agent = agents[rowIndex]
        return when (columnIndex) {
            0 -> agent.displayName
            1 -> agent.executablePath
            2 -> agent.argumentsTemplate
            3 -> agent.workingDirectory
            else -> ""
        }
    }

    fun agentAt(rowIndex: Int): CliAgentDefinition = agents[rowIndex]

    fun addAgent(agent: CliAgentDefinition) {
        agents.add(agent)
        val row = agents.lastIndex
        fireTableRowsInserted(row, row)
    }

    fun updateAgent(rowIndex: Int, agent: CliAgentDefinition) {
        agents[rowIndex] = agent
        fireTableRowsUpdated(rowIndex, rowIndex)
    }

    fun removeAgent(rowIndex: Int) {
        agents.removeAt(rowIndex)
        fireTableRowsDeleted(rowIndex, rowIndex)
    }

    fun snapshot(): List<CliAgentDefinition> = agents.map { it.deepCopy() }

    fun replaceAll(newAgents: List<CliAgentDefinition>) {
        val detached = newAgents.map { it.deepCopy() }
        agents.clear()
        agents.addAll(detached)
        fireTableDataChanged()
    }
}
