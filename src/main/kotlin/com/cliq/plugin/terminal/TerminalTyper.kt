package com.cliq.plugin.terminal

import com.cliq.plugin.CliqPlugin
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.util.concurrency.AppExecutorUtil
import com.jediterm.terminal.ui.JediTermWidget
import java.awt.Component
import java.awt.Container
import java.util.concurrent.TimeUnit

internal object TerminalTyper {

    private const val SUBMIT_DELAY_MS = 60L

    private val log = logger<TerminalTyper>()

    fun typeInActiveTerminal(project: Project, text: String, execute: Boolean = false) {
        ApplicationManager.getApplication().invokeLater(
            {
                val terminalWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(CliqTerminalLauncher.TERMINAL_TOOL_WINDOW_ID)
                if (terminalWindow == null) {
                    notifyNoSession(project)
                    return@invokeLater
                }
                if (terminalWindow.isVisible) {
                    sendToCliqSession(project, terminalWindow, text, execute)
                } else {
                    terminalWindow.show { sendToCliqSession(project, terminalWindow, text, execute) }
                }
            },
            ModalityState.any(),
            project.disposed,
        )
    }

    private fun sendToCliqSession(project: Project, terminalWindow: ToolWindow, text: String, execute: Boolean) {
        val sessions = project.service<CliqTerminalSessions>()
        val contentManager = terminalWindow.contentManager
        val selected = contentManager.selectedContent

        val ordered = mutableListOf<Content>()
        selected?.let(ordered::add)
        contentManager.contents.forEach { if (it !== selected) ordered.add(it) }

        var sawTerminalWidget = false
        for (content in ordered) {
            val widget = findJediTermWidget(content.component) ?: continue
            sawTerminalWidget = true
            if (!sessions.isCliqSession(widget)) continue

            if (content !== selected) contentManager.setSelectedContent(content)
            writeTo(widget, text, execute)
            return
        }

        if (!sawTerminalWidget && contentManager.contents.isNotEmpty()) {
            notifyUnsupportedTerminal(project)
        } else {
            notifyNoSession(project)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeTo(widget: JediTermWidget, text: String, execute: Boolean) {
        try {
            val starter = widget.terminalStarter ?: return
            val payload = "\u001B[200~" + stripControlSequences(text) + "\u001B[201~"
            starter.sendString(payload, false)

            if (execute) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    { runCatching { starter.sendString("\r", false) } },
                    SUBMIT_DELAY_MS, TimeUnit.MILLISECONDS,
                )
            }
        } catch (e: Exception) {
            log.warn(e)
        }
    }

    fun stripControlSequences(text: String): String =
        text.filter { !it.isISOControl() || it == '\n' || it == '\t' }

    private fun findJediTermWidget(component: Component?, depth: Int = 0): JediTermWidget? {
        if (component == null || depth > 32) return null
        if (component is JediTermWidget) return component
        if (component is Container) {
            for (child in component.components) {
                val found = findJediTermWidget(child, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    private fun notifyNoSession(project: Project) {
        notify(
            project,
            "No Cliq agent session",
            "Start an agent from the Cliq tool window (or press Ctrl+Alt+Q) before sending prompts. " +
                "Cliq only writes into terminal tabs it started itself.",
            NotificationType.WARNING,
        )
    }

    private fun notifyUnsupportedTerminal(project: Project) {
        notify(
            project,
            "Unsupported terminal engine",
            "Cliq could not attach to the terminal. Switch back to the classic terminal engine in " +
                "Settings | Tools | Terminal to send prompts and @-context.",
            NotificationType.ERROR,
        )
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CliqPlugin.NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }
}
