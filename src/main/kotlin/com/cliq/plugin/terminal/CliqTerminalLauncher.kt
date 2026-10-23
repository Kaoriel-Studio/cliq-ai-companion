package com.cliq.plugin.terminal

import com.cliq.plugin.CliqPlugin
import com.cliq.plugin.agents.CliAgentDefinition
import com.cliq.plugin.settings.CliqAgentSecrets
import com.cliq.plugin.settings.CliqSettings
import com.cliq.plugin.util.ShellQuoting
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.util.execution.ParametersListUtil
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

@Service(Service.Level.PROJECT)
class CliqTerminalLauncher(private val project: Project) {

    private val log = logger<CliqTerminalLauncher>()

    fun launch(agent: CliAgentDefinition) {
        val executable = agent.executablePath.trim()
        if (executable.isEmpty()) {
            notify(
                "No command configured",
                "Set a CLI executable for ${agent.displayName} in Settings | Tools | Cliq.",
                NotificationType.WARNING,
            )
            return
        }

        val terminalWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
        if (terminalWindow == null) {
            notify(
                "Terminal unavailable",
                "The bundled Terminal tool window could not be located.",
                NotificationType.ERROR,
            )
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val prepared = agent.deepCopy()
            if (CliqAgentSecrets.migrateLegacyValues(prepared)) {
                CliqSettings.getInstance().replaceAgent(prepared)
            }

            val environment = CliqAgentSecrets.load(prepared)
            val flavor = ShellQuoting.detect(project)
            val arguments = ParametersListUtil.parse(prepared.argumentsTemplate)
            val shellCommand = ShellQuoting.command(executable, arguments, flavor)
            val workDirectory = prepared.workingDirectory.trim().ifBlank { project.basePath }

            ApplicationManager.getApplication().invokeLater(
                { startSession(terminalWindow, prepared, environment, shellCommand, workDirectory) },
                ModalityState.any(),
                project.disposed,
            )
        }
    }

    private fun startSession(
        terminalWindow: ToolWindow,
        agent: CliAgentDefinition,
        environment: Map<String, String>,
        shellCommand: String,
        workDirectory: String?,
    ) {
        terminalWindow.show {
            try {
                val sessions = project.service<CliqTerminalSessions>()
                sessions.requestSession(agent.displayName, environment)

                val manager = TerminalToolWindowManager.getInstance(project)
                val widget = manager.createShellWidget(
                    workDirectory ?: project.basePath,
                    "Cliq · ${agent.displayName}",
                    true,
                    true,
                )
                // createShellWidget() returns the Terminal Gen2 TerminalWidget wrapper, not the
                // JediTermWidget instance TerminalTyper finds by walking the tab's Swing tree.
                // Register the unwrapped widget so identity checks in isCliqSession() succeed.
                sessions.registerSession(JBTerminalWidget.asJediTermWidget(widget) ?: widget)

                ApplicationManager.getApplication().invokeLater(
                    {
                        runCatching { widget.sendCommandToExecute(shellCommand) }
                            .onFailure { log.warn("Failed to send command to widget", it) }
                    },
                    ModalityState.any(),
                    project.disposed,
                )
            } catch (t: Throwable) {
                log.warn("Failed to launch ${agent.displayName} via terminal", t)
                notify(
                    "Failed to launch ${agent.displayName}",
                    t.message ?: "Unknown error while creating terminal session.",
                    NotificationType.ERROR,
                )
            }
        }
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CliqPlugin.NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }

    companion object {
        const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
    }
}
