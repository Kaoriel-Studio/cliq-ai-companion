package com.cliq.plugin.settings

import com.cliq.plugin.agents.CliAgentDefinition
import com.cliq.plugin.templates.PromptTemplateManagerDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.util.UUID
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class CliqSettingsConfigurable : Configurable {

    private val settings = CliqSettings.getInstance()

    private val workingAgents: MutableList<CliAgentDefinition> = mutableListOf()
    private val pendingSecrets: MutableMap<String, Map<String, String>> = mutableMapOf()

    private var tableModel: CliAgentTableModel? = null
    private var table: JBTable? = null
    private var autoApplyBox: JCheckBox? = null
    private var promptHistoryBox: JCheckBox? = null
    private var clipboardConfirmBox: JCheckBox? = null
    private var commitPromptArea: JBTextArea? = null

    override fun getDisplayName(): String = "Cliq"

    override fun createComponent(): JComponent {
        workingAgents.clear()
        workingAgents.addAll(settings.agents().map { it.deepCopy() })
        pendingSecrets.clear()

        val model = CliAgentTableModel(workingAgents).also { tableModel = it }

        val agentsTable = JBTable(model).apply {
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            rowHeight = JBUI.scale(22)
        }
        table = agentsTable

        val tablePanel = ToolbarDecorator.createDecorator(agentsTable)
            .setAddAction { addAgent() }
            .setEditAction { editSelected() }
            .setRemoveAction { removeSelected() }
            .setEditActionUpdater { agentsTable.selectedRow >= 0 }
            .setRemoveActionUpdater { agentsTable.selectedRow >= 0 }
            .addExtraAction(object : AnAction("Duplicate", "Duplicate the selected agent", AllIcons.Actions.Copy) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = agentsTable.selectedRow >= 0
                }
                override fun actionPerformed(e: AnActionEvent) = duplicateSelected()
            })
            .disableUpDownActions()
            .createPanel()

        val autoApply = JCheckBox("Auto-apply changes without review", settings.autoApplyChanges)
            .also { autoApplyBox = it }
        val promptHistory = JCheckBox("Keep a history of sent prompts", settings.promptHistoryEnabled)
            .also { promptHistoryBox = it }
        val clipboardConfirm = JCheckBox(
            "Ask before inserting clipboard content into prompts",
            settings.confirmClipboardPlaceholder,
        ).also { clipboardConfirmBox = it }

        val commitPromptField = JBTextArea(settings.commitMessagePromptTemplate, 6, 30).apply {
            lineWrap = true
            wrapStyleWord = true
        }.also { commitPromptArea = it }

        val bottomPanel = panel {
            row { cell(autoApply) }
            row {
                comment("If enabled, proposed code changes are written directly to disk, inside the project only.")
            }
            row { cell(promptHistory) }
            row {
                comment("History is stored per project in workspace.xml. Prompts built from the clipboard are never stored.")
            }
            row { cell(clipboardConfirm) }
            separator()
            row("Prompt Templates:") {
                link("Manage prompt templates...") {
                    PromptTemplateManagerDialog().show()
                }
            }
            row {
                comment("Add, edit, or remove the templates offered by the prompt picker in the chat input.")
            }
            separator()
            row("Commit Message Prompt:") {}
            row {
                cell(JBScrollPane(commitPromptField))
                    .align(Align.FILL)
            }.resizableRow()
            row {
                comment(
                    "Used by the commit-message icon above the chat input and by Tools → Cliq: Generate Commit " +
                        "Message. Include ${CliqSettings.COMMIT_DIFF_PLACEHOLDER} where the diff should be inserted."
                )
            }
            row {
                link("Reset to default") {
                    commitPromptField.text = CliqSettings.DEFAULT_COMMIT_MESSAGE_PROMPT_TEMPLATE
                }
            }
            separator()
            row("Keyboard Shortcuts:") {
                link("Configure shortcuts in Keymap...") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(null, "Keymap")
                }
            }
            row {
                comment("Search for 'Cliq: Send Files' to assign a custom shortcut.")
            }
        }

        return JPanel(BorderLayout(0, 12)).apply {
            border = JBUI.Borders.empty(4)
            add(tablePanel, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }
    }

    private fun environmentOf(agent: CliAgentDefinition): Map<String, String> =
        pendingSecrets[agent.id] ?: CliqAgentSecrets.load(agent)

    private fun addAgent() {
        val model = tableModel ?: return
        val existingNames = model.snapshot().map { it.displayName }.toSet()
        val dialog = CliAgentEditDialog(CliAgentDefinition(), existingNames, emptyMap())
        if (dialog.showAndGet()) {
            val created = dialog.buildResult()
            pendingSecrets[created.id] = dialog.environmentValues()
            model.addAgent(created)
        }
    }

    private fun editSelected() {
        val model = tableModel ?: return
        val row = selectedModelRow() ?: return
        val current = model.agentAt(row)
        val existingNames = model.snapshot()
            .filterIndexed { index, _ -> index != row }
            .map { it.displayName }
            .toSet()

        val dialog = CliAgentEditDialog(current, existingNames, environmentOf(current))
        if (dialog.showAndGet()) {
            val updated = dialog.buildResult()
            pendingSecrets[updated.id] = dialog.environmentValues()
            model.updateAgent(row, updated)
        }
    }

    private fun duplicateSelected() {
        val model = tableModel ?: return
        val row = selectedModelRow() ?: return
        val source = model.agentAt(row)
        val existingNames = model.snapshot().map { it.displayName }.toSet()

        var candidateName = "${source.displayName} (copy)"
        var suffix = 2
        while (candidateName in existingNames) {
            candidateName = "${source.displayName} (copy $suffix)"
            suffix++
        }

        val sourceEnvironment = environmentOf(source)
        val duplicate = source.deepCopy().apply {
            id = UUID.randomUUID().toString()
            displayName = candidateName
            environmentKeys = sourceEnvironment.keys.toMutableList()
            environmentVariables = mutableMapOf()
        }

        pendingSecrets[duplicate.id] = sourceEnvironment
        model.addAgent(duplicate)
    }

    private fun removeSelected() {
        val model = tableModel ?: return
        val row = selectedModelRow() ?: return
        model.removeAgent(row)
    }

    private fun selectedModelRow(): Int? {
        val view = table?.selectedRow ?: return null
        if (view < 0) return null
        return table?.convertRowIndexToModel(view)?.takeIf { it >= 0 }
    }

    override fun isModified(): Boolean {
        val model = tableModel ?: return false
        return model.snapshot() != settings.agents() ||
            pendingSecrets.isNotEmpty() ||
            autoApplyBox?.isSelected != settings.autoApplyChanges ||
            promptHistoryBox?.isSelected != settings.promptHistoryEnabled ||
            clipboardConfirmBox?.isSelected != settings.confirmClipboardPlaceholder ||
            commitPromptArea?.text != settings.commitMessagePromptTemplate
    }

    override fun apply() {
        val model = tableModel ?: return
        val agents = model.snapshot()

        if (agents.isEmpty()) {
            throw ConfigurationException("At least one CLI agent must be configured.")
        }

        val invalidAgent = agents.firstOrNull { !it.isValid() }
        if (invalidAgent != null) {
            val label = invalidAgent.displayName.ifBlank { "(unnamed)" }
            throw ConfigurationException("Agent '$label' requires both a display name and an executable path.")
        }

        val duplicateGroup = agents.groupBy { it.displayName.trim() }.values.firstOrNull { it.size > 1 }
        if (duplicateGroup != null) {
            throw ConfigurationException("Agent names must be unique. Duplicate name: '${duplicateGroup.first().displayName}'.")
        }

        val removedAgents = settings.agents().filter { previous -> agents.none { it.id == previous.id } }
        val secretsToWrite = pendingSecrets.toMap()
        pendingSecrets.clear()

        settings.setAgents(agents)
        settings.autoApplyChanges = autoApplyBox?.isSelected ?: settings.autoApplyChanges
        settings.promptHistoryEnabled = promptHistoryBox?.isSelected ?: settings.promptHistoryEnabled
        settings.confirmClipboardPlaceholder =
            clipboardConfirmBox?.isSelected ?: settings.confirmClipboardPlaceholder
        settings.commitMessagePromptTemplate =
            commitPromptArea?.text ?: settings.commitMessagePromptTemplate

        ApplicationManager.getApplication().executeOnPooledThread {
            removedAgents.forEach { CliqAgentSecrets.forget(it.id, it.environmentKeys + it.environmentVariables.keys) }
            secretsToWrite.forEach { (agentId, environment) ->
                val stale = settings.agents().firstOrNull { it.id == agentId }
                    ?.environmentKeys
                    ?.filterNot { environment.containsKey(it) }
                    .orEmpty()
                if (stale.isNotEmpty()) CliqAgentSecrets.forget(agentId, stale)
                CliqAgentSecrets.store(agentId, environment)
            }
        }
    }

    override fun reset() {
        workingAgents.clear()
        pendingSecrets.clear()
        tableModel?.replaceAll(settings.agents().map { it.deepCopy() })
        autoApplyBox?.isSelected = settings.autoApplyChanges
        promptHistoryBox?.isSelected = settings.promptHistoryEnabled
        clipboardConfirmBox?.isSelected = settings.confirmClipboardPlaceholder
        commitPromptArea?.text = settings.commitMessagePromptTemplate
    }

    override fun disposeUIResources() {
        pendingSecrets.clear()
        tableModel = null
        table = null
        autoApplyBox = null
        promptHistoryBox = null
        clipboardConfirmBox = null
        commitPromptArea = null
    }
}
