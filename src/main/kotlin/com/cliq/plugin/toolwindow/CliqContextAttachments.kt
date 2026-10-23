package com.cliq.plugin.toolwindow

import com.cliq.plugin.CliqPlugin
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import java.util.EventListener
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class CliqContextAttachments(private val project: Project) {

    class Attachment(
        val files: List<VirtualFile>,
        val replacedDirectory: String?,
        val prompt: String?,
    )

    fun interface Listener : EventListener {
        fun onAttachmentAvailable()
    }

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic.create("Cliq Context Attachments", Listener::class.java)
    }

    private val pending = AtomicReference<Attachment?>(null)

    fun consume(): Attachment? = pending.getAndSet(null)

    fun deliver(attachment: Attachment, onUndelivered: (Attachment) -> Unit) {
        pending.set(attachment)
        ApplicationManager.getApplication().invokeLater(
            {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(CliqPlugin.TOOL_WINDOW_ID)
                if (toolWindow == null) {
                    consume()?.let(onUndelivered)
                    return@invokeLater
                }
                toolWindow.show {
                    project.messageBus.syncPublisher(TOPIC).onAttachmentAvailable()
                }
            },
            ModalityState.any(),
            project.disposed,
        )
    }
}
