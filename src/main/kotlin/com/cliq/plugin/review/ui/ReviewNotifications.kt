package com.cliq.plugin.review.ui

import com.cliq.plugin.CliqPlugin
import com.cliq.plugin.review.settings.CliqReviewConfigurable
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.xml.util.XmlStringUtil

object ReviewNotifications {

    fun create(title: String, content: String, type: NotificationType): Notification =
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CliqPlugin.NOTIFICATION_GROUP)
            .createNotification(XmlStringUtil.escapeString(title), XmlStringUtil.escapeString(content), type)

    fun notify(project: Project, title: String, content: String, type: NotificationType) {
        create(title, content, type).notify(project)
    }

    fun notifyWithSettings(project: Project, title: String, content: String, type: NotificationType) {
        val notification = create(title, content, type)
        notification.addAction(
            NotificationAction.createSimpleExpiring("Open code review settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, CliqReviewConfigurable::class.java)
            },
        )
        notification.notify(project)
    }
}
