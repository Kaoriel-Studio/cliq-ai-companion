package com.cliq.plugin.toolwindow

import com.cliq.plugin.settings.CliqSettings
import com.cliq.plugin.settings.CliqSettingsConfigurable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CliqToolWindowFactory : ToolWindowFactory, DumbAware {

    private companion object {
        const val GENERATE_COMMIT_MESSAGE_ACTION_ID = "com.cliq.plugin.GenerateCommitMessage"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CliqToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)

        val gear = DefaultActionGroup().apply {
            add(object : ToggleAction("Auto-apply Changes Without Review") {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun isSelected(e: AnActionEvent) = CliqSettings.getInstance().autoApplyChanges
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    CliqSettings.getInstance().autoApplyChanges = state
                }
            })
            ActionManager.getInstance().getAction(GENERATE_COMMIT_MESSAGE_ACTION_ID)?.let(::add)
            addSeparator()
            add(object : AnAction("Cliq Settings…") {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, CliqSettingsConfigurable::class.java)
                }
            })
        }
        toolWindow.setAdditionalGearActions(gear)
    }
}
