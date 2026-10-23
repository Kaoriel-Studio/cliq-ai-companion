package com.cliq.plugin.actions

import com.cliq.plugin.agents.CliAgentDefinition
import com.cliq.plugin.settings.CliqSettings
import com.cliq.plugin.terminal.CliqTerminalLauncher
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.popup.JBPopupFactory

class CliqAgentAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val launcher = project.service<CliqTerminalLauncher>()
        val agents = CliqSettings.getInstance().agents()

        when (agents.size) {
            0 -> return
            1 -> launcher.launch(agents.single())
            else -> showAgentPicker(e, agents) { launcher.launch(it) }
        }
    }

    private fun showAgentPicker(
        e: AnActionEvent,
        agents: List<CliAgentDefinition>,
        onChosen: (CliAgentDefinition) -> Unit,
    ) {
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(agents)
            .setTitle("Run Cliq Agent")
            .setItemChosenCallback { chosen -> onChosen(chosen) }
            .setRenderer { _, value, _, _, _ ->
                javax.swing.JLabel("${value.displayName}  —  ${value.executablePath}").apply {
                    border = javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8)
                }
            }
            .createPopup()

        val component = e.inputEvent?.component
        if (component != null) {
            popup.showUnderneathOf(component)
        } else {
            popup.showCenteredInCurrentWindow(e.project!!)
        }
    }
}
