package com.cliq.plugin.settings

import com.cliq.plugin.agents.CliAgentDefinition
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

class CliAgentEditDialog(
    private val initial: CliAgentDefinition,
    private val existingDisplayNames: Set<String>,
    initialEnvironment: Map<String, String>,
) : DialogWrapper(true) {

    private companion object {
        val ENVIRONMENT_KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
        const val MAX_FIELD_LENGTH = 4_096
    }

    private val displayNameField = JBTextField(initial.displayName, 30)
    private val executableField = JBTextField(initial.executablePath, 30)
    private val argumentsField = JBTextField(initial.argumentsTemplate, 30)
    private val workingDirectoryField = JBTextField(initial.workingDirectory, 30)
    private val environmentArea = JBTextArea(
        initialEnvironment.entries.joinToString("\n") { (key, value) -> "$key=$value" },
        6,
        30,
    )

    init {
        setTitle(if (initial.displayName.isBlank()) "Add CLI Agent" else "Edit CLI Agent")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Display name:") {
            cell(displayNameField).columns(30)
        }.layout(RowLayout.LABEL_ALIGNED)
        row("Executable path:") {
            cell(executableField).columns(30)
        }.layout(RowLayout.LABEL_ALIGNED)
        row {
            comment("Executable name resolved via PATH, or an absolute path to the CLI binary.")
        }
        row("Arguments:") {
            cell(argumentsField).columns(30)
        }.layout(RowLayout.LABEL_ALIGNED)
        row {
            comment("Space-separated CLI flags, e.g. --model sonnet --yolo. Quote values containing spaces.")
        }
        row("Working directory:") {
            cell(workingDirectoryField).columns(30)
        }.layout(RowLayout.LABEL_ALIGNED)
        row {
            comment("Leave empty to use the current project's root directory.")
        }
        row("Environment variables:") {}
        row {
            cell(JBScrollPane(environmentArea))
                .align(Align.FILL)
        }.resizableRow()
        row {
            comment(
                "One KEY=VALUE pair per line. Values are stored in the IDE password safe, " +
                    "not in plain-text settings, and are passed to the agent's terminal session."
            )
        }
    }

    override fun doValidate(): ValidationInfo? {
        val name = displayNameField.text.trim()
        val executable = executableField.text.trim()

        if (name.isEmpty()) return ValidationInfo("Display name must not be empty.", displayNameField)
        if (name.length > MAX_FIELD_LENGTH) return ValidationInfo("Display name is too long.", displayNameField)
        if (executable.isEmpty()) return ValidationInfo("Executable path must not be empty.", executableField)
        if (executable.length > MAX_FIELD_LENGTH) return ValidationInfo("Executable path is too long.", executableField)
        if (argumentsField.text.length > MAX_FIELD_LENGTH) {
            return ValidationInfo("Arguments are too long.", argumentsField)
        }
        if (name != initial.displayName && existingDisplayNames.contains(name)) {
            return ValidationInfo("An agent named '$name' already exists.", displayNameField)
        }

        val workingDirectory = workingDirectoryField.text.trim()
        if (workingDirectory.isNotEmpty() && !java.io.File(workingDirectory).isDirectory) {
            return ValidationInfo("Working directory does not exist.", workingDirectoryField)
        }

        return validateEnvironment()
    }

    private fun validateEnvironment(): ValidationInfo? {
        environmentArea.text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex <= 0) {
                    return ValidationInfo(
                        "Environment variables must be in KEY=VALUE format, one per line.",
                        environmentArea,
                    )
                }
                val key = line.substring(0, separatorIndex).trim()
                if (!ENVIRONMENT_KEY_PATTERN.matches(key)) {
                    return ValidationInfo("'$key' is not a valid environment variable name.", environmentArea)
                }
            }
        return null
    }

    fun environmentValues(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        environmentArea.text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex <= 0) return@forEach
                val key = line.substring(0, separatorIndex).trim()
                if (!ENVIRONMENT_KEY_PATTERN.matches(key)) return@forEach
                result[key] = line.substring(separatorIndex + 1).trim()
            }
        return result
    }

    fun buildResult(): CliAgentDefinition = initial.copy(
        displayName = displayNameField.text.trim(),
        executablePath = executableField.text.trim(),
        argumentsTemplate = argumentsField.text.trim(),
        workingDirectory = workingDirectoryField.text.trim(),
        environmentKeys = environmentValues().keys.toMutableList(),
        environmentVariables = mutableMapOf(),
    )
}
