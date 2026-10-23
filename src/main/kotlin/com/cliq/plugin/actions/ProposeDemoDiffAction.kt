package com.cliq.plugin.actions

import com.cliq.plugin.diff.CliqDiffManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil

class ProposeDemoDiffAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null &&
            e.getData(CommonDataKeys.VIRTUAL_FILE)?.isInLocalFileSystem == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (!file.isInLocalFileSystem) return

        val current = runCatching {
            ApplicationManager.getApplication().runReadAction<String> {
                VfsUtil.loadText(file)
            }
        }.getOrElse {
            notify(project, "Cannot read file: ${it.message}")
            return
        }

        val proposed = "$current"
        project.service<CliqDiffManager>().showDiff(file.path, proposed)
    }

    private fun notify(project: com.intellij.openapi.project.Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(com.cliq.plugin.CliqPlugin.NOTIFICATION_GROUP)
            .createNotification("Cliq demo diff", message, NotificationType.WARNING)
            .notify(project)
    }
}
