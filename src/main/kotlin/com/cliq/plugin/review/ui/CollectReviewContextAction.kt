package com.cliq.plugin.review.ui

import com.cliq.plugin.review.connect.GitRemoteLocation
import com.cliq.plugin.review.connect.HostUrls
import com.cliq.plugin.review.connect.ReviewTargetResolver
import com.cliq.plugin.review.provider.ProviderKind
import com.cliq.plugin.review.settings.CliqReviewSettings
import com.cliq.plugin.review.settings.ReviewHost
import com.cliq.plugin.review.settings.ReviewHostEditDialog
import com.cliq.plugin.review.settings.ReviewHostStore
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.annotations.RequiresEdt

class CollectReviewContextAction : AnAction(), DumbAware {

    companion object {
        @RequiresEdt
        fun start(project: Project) {
            if (project.basePath == null) return
            val store = ReviewHostStore.of(project)
            val target = ReviewTargetResolver.resolve(project, store)
            val host = target.host ?: chooseOrAddHost(project, store, target.remote) ?: return
            val hosts = store.hosts().ifEmpty { listOf(host) }

            val dialog = ReviewRequestPickerDialog(project, store, hosts, host, target.projectPath, target.currentBranch)
            if (!dialog.showAndGet()) return
            val selection = dialog.selection() ?: return
            CollectReviewContextTask(project, selection).queue()
        }

        private fun chooseOrAddHost(project: Project, store: ReviewHostStore, remote: GitRemoteLocation?): ReviewHost? {
            val configured = store.hosts()
            if (configured.isEmpty()) return addHost(project, store, remote)
            if (remote == null) return configured.first()

            val choice = MessageDialogBuilder
                .yesNoCancel(
                    "Unknown Review Host",
                    "The git remote points to ${remote.host}, which is not configured in Cliq.\n\n" +
                        "Add it as a new host, or pick one of the configured hosts?",
                )
                .yesText("Add Host…")
                .noText("Use Configured Host")
                .show(project)
            return when (choice) {
                Messages.YES -> addHost(project, store, remote)
                Messages.NO -> configured.first()
                else -> null
            }
        }

        fun addHost(project: Project, store: ReviewHostStore, remote: GitRemoteLocation?): ReviewHost? {
            val dialog = ReviewHostEditDialog(
                project = project,
                store = store,
                initial = null,
                suggestedUrl = remote?.let(HostUrls::suggestedUrl),
                suggestedKind = remote?.let { ProviderKind.guess(it.host) },
                existingUrls = store.hosts().map { it.url }.toSet(),
                connectionOptions = CliqReviewSettings.getInstance()::connectionOptions,
                dialogTitle = "Add Code Review Host",
            )
            if (!dialog.showAndGet()) return null
            val result = dialog.result() ?: return null
            store.upsertHost(result.host.copy(tokenStored = false))
            result.newToken?.let { token ->
                ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    { store.storeToken(result.host, token) },
                    "Saving Token",
                    false,
                    project,
                )
            }
            val saved = store.host(result.host.url)
            if (saved == null) {
                ReviewNotifications.notify(project, "Host not saved", "Cliq could not save ${result.host.url}.", NotificationType.ERROR)
            }
            return saved
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(::start)
    }
}
