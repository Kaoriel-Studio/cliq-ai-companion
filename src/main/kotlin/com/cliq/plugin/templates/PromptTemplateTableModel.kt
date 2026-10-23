package com.cliq.plugin.templates

import com.intellij.util.ui.EditableModel
import javax.swing.table.AbstractTableModel

class PromptTemplateTableModel(private val templates: MutableList<PromptTemplate>) :
    AbstractTableModel(), EditableModel {

    private val columns = listOf("Title", "Description")

    override fun getRowCount(): Int = templates.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val template = templates[rowIndex]
        return when (columnIndex) {
            0 -> template.title
            1 -> template.description
            else -> ""
        }
    }

    fun templateAt(rowIndex: Int): PromptTemplate = templates[rowIndex]

    fun addTemplate(template: PromptTemplate) {
        templates.add(template)
        val row = templates.lastIndex
        fireTableRowsInserted(row, row)
    }

    fun updateTemplate(rowIndex: Int, template: PromptTemplate) {
        templates[rowIndex] = template
        fireTableRowsUpdated(rowIndex, rowIndex)
    }

    fun removeTemplateAt(rowIndex: Int) {
        templates.removeAt(rowIndex)
        fireTableRowsDeleted(rowIndex, rowIndex)
    }

    fun snapshot(): List<PromptTemplate> = templates.map { it.copy() }

    override fun addRow() {
        addTemplate(PromptTemplate(title = "New Template"))
    }

    override fun removeRow(index: Int) = removeTemplateAt(index)

    override fun exchangeRows(oldIndex: Int, newIndex: Int) {
        val moved = templates.removeAt(oldIndex)
        templates.add(newIndex, moved)
        fireTableRowsUpdated(minOf(oldIndex, newIndex), maxOf(oldIndex, newIndex))
    }

    override fun canExchangeRows(oldIndex: Int, newIndex: Int): Boolean =
        newIndex in templates.indices
}
