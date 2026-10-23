package com.cliq.plugin.vcs

import com.cliq.plugin.CliqPlugin
import com.cliq.plugin.settings.CliqSettings
import com.cliq.plugin.toolwindow.CliqPendingPrompt
import com.cliq.plugin.toolwindow.CliqToolWindowPanel
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diff.impl.patch.FilePatch
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.datatransfer.StringSelection
import java.io.StringWriter

class GenerateCommitMessageAction : AnAction() {

    private companion object {
        const val MAX_DIFF_LENGTH = 20_000
        const val TRUNCATION_NOTE = "\n... [diff truncated]"
    }

    private val log = logger<GenerateCommitMessageAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Cliq: Building Diff for Commit Message", true) {
                override fun run(indicator: ProgressIndicator) {
                    val changes = ReadAction.compute<List<Change>, RuntimeException> {
                        if (project.isDisposed) emptyList() else ChangeListManager.getInstance(project).allChanges.toList()
                    }

                    if (changes.isEmpty()) {
                        notify(
                            project,
                            "No changes found",
                            "There are no pending changes to summarize.",
                            NotificationType.WARNING,
                        )
                        return
                    }

                    indicator.checkCanceled()

                    val diffText = runCatching { buildDiffText(project, changes) }
                        .onFailure { log.warn("Failed to build diff for commit message", it) }
                        .getOrNull()

                    if (diffText.isNullOrBlank()) {
                        notify(
                            project,
                            "Could not build diff",
                            "Failed to build a diff from the current changes.",
                            NotificationType.ERROR,
                        )
                        return
                    }

                    indicator.checkCanceled()
                    deliverPrompt(project, buildPrompt(diffText))
                }
            }
        )
    }

    private fun buildDiffText(project: Project, changes: Collection<Change>): String {
        val basePath = project.basePath ?: "."
        val patches = ReadAction.compute<List<FilePatch>, VcsException> {
            IdeaTextPatchBuilder.buildPatch(project, changes, basePath, false)
        }

        val writer = StringWriter()
        ReadAction.run<Exception> {
            UnifiedDiffWriter.write(project, patches, writer, "\n", null)
        }
        return truncate(writer.toString())
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_DIFF_LENGTH) return text
        val cut = text.take(MAX_DIFF_LENGTH)
        val lastLineBreak = cut.lastIndexOf('\n')
        val safeCut = if (lastLineBreak > 0) cut.substring(0, lastLineBreak) else cut
        return safeCut + TRUNCATION_NOTE
    }

    private fun buildPrompt(diffText: String): String {
        val template = CliqSettings.getInstance().commitMessagePromptTemplate
        val diffBlock = "```diff\n$diffText\n```"
        return if (template.contains(CliqSettings.COMMIT_DIFF_PLACEHOLDER)) {
            template.replace(CliqSettings.COMMIT_DIFF_PLACEHOLDER, diffBlock)
        } else {
            "${template.trimEnd()}\n\n$diffBlock"
        }
    }

    private fun deliverPrompt(project: Project, prompt: String) {
        project.service<CliqPendingPrompt>().put(prompt)

        ApplicationManager.getApplication().invokeLater(
            {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(CliqPlugin.TOOL_WINDOW_ID)
                if (toolWindow == null) {
                    notifyUndelivered(project, prompt)
                    return@invokeLater
                }
                toolWindow.show {
                    project.messageBus
                        .syncPublisher(CliqToolWindowPanel.INSERT_PROMPT_TOPIC)
                        .onInsertPromptTextRequested(prompt)

                    if (project.service<CliqPendingPrompt>().peek() != null) {
                        notifyUndelivered(project, prompt)
                    }
                }
            },
            ModalityState.any(),
            project.disposed,
        )
    }

    private fun notifyUndelivered(project: Project, prompt: String) {
        project.service<CliqPendingPrompt>().consume()

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(CliqPlugin.NOTIFICATION_GROUP)
            .createNotification(
                "Commit message prompt not delivered",
                "The Cliq tool window did not accept the generated prompt.",
                NotificationType.WARNING,
            )

        notification.addAction(
            NotificationAction.createSimple("Copy prompt to clipboard") {
                CopyPasteManager.getInstance().setContents(StringSelection(prompt))
                notification.expire()
            }
        )
        notification.notify(project)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CliqPlugin.NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }
}
