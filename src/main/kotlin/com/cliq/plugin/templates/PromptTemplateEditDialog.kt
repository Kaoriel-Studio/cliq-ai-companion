package com.cliq.plugin.templates

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class PromptTemplateEditDialog(
    private val initial: PromptTemplate,
    private val existingTitles: Set<String>,
) : DialogWrapper(true) {

    private val titleField = JBTextField(initial.title, 30)
    private val descriptionField = JBTextField(initial.description, 30)
    private val contentArea = JBTextArea(initial.content, 10, 30).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        setTitle(if (initial.title.isBlank()) "Add Prompt Template" else "Edit Prompt Template")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Title:") {
            cell(titleField).columns(30)
        }.layout(RowLayout.LABEL_ALIGNED)
        row("Description:") {
            cell(descriptionField).columns(30)
        }.layout(RowLayout.LABEL_ALIGNED)
        row {
            comment("Optional short helper text shown in the template picker.")
        }
        row("Content:") {}
        row {
            cell(JBScrollPane(contentArea))
                .align(Align.FILL)
        }.resizableRow()
        row {
            comment("The full prompt text inserted into the chat input. Supports {{activeFile}}, {{selection}}, and {{clipboard}} placeholders.")
        }
    }

    override fun doValidate(): ValidationInfo? {
        val title = titleField.text.trim()
        val content = contentArea.text.trim()
        if (title.isEmpty()) return ValidationInfo("Title must not be empty.", titleField)
        if (content.isEmpty()) return ValidationInfo("Content must not be empty.", contentArea)
        if (title != initial.title && existingTitles.contains(title)) {
            return ValidationInfo("A template titled '$title' already exists.", titleField)
        }
        return null
    }

    fun buildResult(): PromptTemplate = initial.copy(
        title = titleField.text.trim(),
        description = descriptionField.text.trim(),
        content = contentArea.text,
    )
}
