package com.cliq.plugin.templates

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class PromptTemplateManagerDialog : DialogWrapper(true) {

    private val service = CliqPromptTemplates.getInstance()
    private val workingTemplates: MutableList<PromptTemplate> =
        service.templates().map { it.copy() }.toMutableList()
    private val tableModel = PromptTemplateTableModel(workingTemplates)
    private val table = JBTable(tableModel).apply {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        rowHeight = JBUI.scale(22)
    }

    init {
        setTitle("Manage Prompt Templates")
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tablePanel = ToolbarDecorator.createDecorator(table)
            .setAddAction { addTemplate() }
            .setEditAction { editSelected() }
            .setRemoveAction { removeSelected() }
            .setEditActionUpdater { table.selectedRow >= 0 }
            .setRemoveActionUpdater { table.selectedRow >= 0 }
            .createPanel()

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(520, 360)
            add(tablePanel, BorderLayout.CENTER)
        }
    }

    private fun addTemplate() {
        val existingTitles = tableModel.snapshot().map { it.title }.toSet()
        val dialog = PromptTemplateEditDialog(PromptTemplate(), existingTitles)
        if (dialog.showAndGet()) {
            tableModel.addTemplate(dialog.buildResult())
        }
    }

    private fun editSelected() {
        val row = table.selectedRow
        if (row < 0) return
        val current = tableModel.templateAt(row)
        val existingTitles = tableModel.snapshot()
            .filterIndexed { index, _ -> index != row }
            .map { it.title }
            .toSet()
        val dialog = PromptTemplateEditDialog(current, existingTitles)
        if (dialog.showAndGet()) {
            tableModel.updateTemplate(row, dialog.buildResult())
        }
    }

    private fun removeSelected() {
        val row = table.selectedRow
        if (row < 0) return
        tableModel.removeTemplateAt(row)
    }

    override fun doValidate(): ValidationInfo? {
        if (tableModel.snapshot().isEmpty()) {
            return ValidationInfo("At least one prompt template must be configured.")
        }
        val duplicateGroup = tableModel.snapshot().groupBy { it.title.trim() }.values.firstOrNull { it.size > 1 }
        if (duplicateGroup != null) {
            return ValidationInfo("Template titles must be unique. Duplicate: '${duplicateGroup.first().title}'.")
        }
        return null
    }

    override fun doOKAction() {
        service.setTemplates(tableModel.snapshot())
        super.doOKAction()
    }
}
