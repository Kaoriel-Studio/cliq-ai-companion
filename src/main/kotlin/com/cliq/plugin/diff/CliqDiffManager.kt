package com.cliq.plugin.diff

import com.cliq.plugin.CliqPlugin
import com.cliq.plugin.mcp.CliqIdeServer
import com.cliq.plugin.util.CliqPathGuard
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.Side
import com.intellij.ide.impl.isTrusted
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diff.DiffBundle
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.util.messages.Topic
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.EventListener
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class CliqDiffManager(private val project: Project) : Disposable {

    enum class Origin { AGENT, LOCAL }

    private val log = logger<CliqDiffManager>()
    private val reviews = java.util.concurrent.ConcurrentHashMap<String, ReviewState>()
    private val listeners = CopyOnWriteArrayList<DiffListener>()

    interface DiffListener : EventListener {
        fun onDiffOutcome(outcome: CliqDiffOutcome)
    }

    interface PendingReviewsListener : EventListener {
        fun onPendingReviewsChanged(pendingFilePaths: List<String>)
    }

    companion object {
        val FILE_PATH_KEY: Key<String> = Key.create("cliq.diff.filePath")
        val TOPIC: Topic<DiffListener> = Topic.create("Cliq Diff Outcome", DiffListener::class.java)
        val PENDING_TOPIC: Topic<PendingReviewsListener> =
            Topic.create("Cliq Diff Pending Changed", PendingReviewsListener::class.java)
    }

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (file is ChainDiffVirtualFile) {
                    val producer = file.chain.requests.firstOrNull() as? SimpleDiffRequestChain.DiffRequestProducerWrapper
                    val path = producer?.request?.getUserData(FILE_PATH_KEY) ?: return

                    if (reviews.containsKey(path)) {
                        log.info("Diff tab for $path closed manually, rejecting review.")
                        reject(path)
                    }
                }
            }
        })
    }

    fun addListener(listener: DiffListener) { listeners.add(listener) }
    fun removeListener(listener: DiffListener) { listeners.remove(listener) }

    fun pendingFilePaths(): List<String> = reviews.keys.toList()

    fun validateTarget(filePath: String): String? {
        if (!project.isTrusted()) {
            return "Project is not trusted. Cliq does not modify files in untrusted projects."
        }
        if (CliqPathGuard.resolveInsideProject(project, filePath) == null) {
            return "Refusing to touch a path outside the project: $filePath"
        }
        return null
    }

    fun acceptAll() {
        pendingFilePaths().forEach { accept(it) }
    }

    fun rejectAll() {
        pendingFilePaths().forEach { reject(it) }
    }

    fun focusDiff(filePath: String) {
        onEdt {
            val virtualFile = findDiffVirtualFile(filePath) ?: return@onEdt
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    }

    fun showDiff(filePath: String, proposedContent: String, origin: Origin = Origin.LOCAL) {
        val rejection = validateTarget(filePath)
        if (rejection != null) {
            log.warn("Rejected diff request for $filePath: $rejection")
            notify("Cliq diff blocked", rejection, NotificationType.ERROR)
            return
        }

        onEdt {
            if (reviews.containsKey(filePath)) {
                closeDiff(filePath, suppressNotification = true)
            }
            openDiffTab(filePath, proposedContent, origin)
        }
    }

    private fun openDiffTab(filePath: String, proposedContent: String, origin: Origin) {
        val factory = DiffContentFactory.getInstance()
        val existing = LocalFileSystem.getInstance().findFileByPath(filePath)
        val fileType: FileType = existing?.fileType
            ?: FileTypeRegistry.getInstance().getFileTypeByFileName(Paths.get(filePath).fileName.toString())

        val left = if (existing != null) {
            factory.create(project, existing)
        } else {
            factory.create("", fileType)
        }
        val right = factory.createEditable(project, proposedContent, fileType)

        val title = "${Paths.get(filePath).fileName} · Cliq review"
        val request = SimpleDiffRequest(title, left, right, "Current", "Proposed")

        request.putUserData(DiffUserDataKeys.MASTER_SIDE, Side.RIGHT)
        request.putUserData(DiffUserDataKeys.PREFERRED_FOCUS_SIDE, Side.RIGHT)
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, false))
        request.putUserData(FILE_PATH_KEY, filePath)

        request.putUserData(DiffUserDataKeysEx.LAST_REVISION_WITH_LOCAL, true)
        request.putUserData(DiffUserDataKeysEx.VCS_DIFF_ACCEPT_LEFT_ACTION_TEXT, DiffBundle.message("action.presentation.diff.revert.text"))

        request.putUserData(
            DiffUserDataKeys.CONTEXT_ACTIONS,
            listOf(RejectCliqDiffAction(), AcceptCliqDiffAction()),
        )

        reviews[filePath] = ReviewState(filePath, proposedContent, origin)
        notifyPendingChanged()

        val chain = SimpleDiffRequestChain(request)
        val virtualFile = ChainDiffVirtualFile(chain, title)
        val lastFocusedComponent = IdeFocusManager.getInstance(project).focusOwner
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        lastFocusedComponent?.let {
            IdeFocusManager.getInstance(project).requestFocus(it, true)
        }
    }

    fun accept(filePath: String) {
        onEdt {
            val review = reviews.remove(filePath) ?: return@onEdt
            val finalText = readRightSideText(filePath) ?: review.proposedContent

            if (!willAgentApply(review)) {
                val failure = runCatching { writeFile(filePath, finalText) }.exceptionOrNull()
                if (failure != null) {
                    log.warn("Failed to apply $filePath", failure)
                    notify(
                        "Cliq diff",
                        "Failed to apply changes to $filePath: ${failure.message ?: failure.javaClass.simpleName}",
                        NotificationType.ERROR,
                    )
                    finishReviewAfterRemoved(filePath, CliqDiffOutcome.Rejected(filePath))
                    return@onEdt
                }
            }

            finishReviewAfterRemoved(filePath, CliqDiffOutcome.Accepted(filePath, finalText))
        }
    }

    private fun willAgentApply(review: ReviewState): Boolean =
        review.origin == Origin.AGENT && project.service<CliqIdeServer>().hasActiveSessions()

    fun applyDirectly(filePath: String, newContent: String) {
        val rejection = validateTarget(filePath)
        if (rejection != null) {
            log.warn("Rejected auto-apply for $filePath: $rejection")
            notify("Cliq diff blocked", rejection, NotificationType.ERROR)
            return
        }

        onEdt {
            val outcome: CliqDiffOutcome = try {
                writeFile(filePath, newContent)
                CliqDiffOutcome.Accepted(filePath, newContent)
            } catch (t: Throwable) {
                log.warn("Failed to auto-apply $filePath", t)
                notify(
                    "Cliq diff",
                    "Failed to auto-apply changes to $filePath: ${t.message ?: t.javaClass.simpleName}",
                    NotificationType.ERROR,
                )
                CliqDiffOutcome.Rejected(filePath)
            }
            listeners.forEach { runCatching { it.onDiffOutcome(outcome) }.onFailure { log.warn(it) } }
            project.messageBus.syncPublisher(TOPIC).onDiffOutcome(outcome)
        }
    }

    fun reject(filePath: String) {
        onEdt {
            reviews.remove(filePath) ?: return@onEdt
            finishReviewAfterRemoved(filePath, CliqDiffOutcome.Rejected(filePath))
        }
    }

    fun closeDiff(filePath: String, suppressNotification: Boolean = false): String? {
        val review = reviews.remove(filePath) ?: return null
        val text = readRightSideText(filePath) ?: review.proposedContent
        finishReviewAfterRemoved(filePath, CliqDiffOutcome.Rejected(filePath, suppressNotification))
        return text
    }

    private fun finishReviewAfterRemoved(filePath: String, outcome: CliqDiffOutcome) {
        closeDiffTab(filePath)
        notifyPendingChanged()
        listeners.forEach { runCatching { it.onDiffOutcome(outcome) }.onFailure { log.warn(it) } }
        project.messageBus.syncPublisher(TOPIC).onDiffOutcome(outcome)
    }

    private fun notifyPendingChanged() {
        project.messageBus.syncPublisher(PENDING_TOPIC).onPendingReviewsChanged(pendingFilePaths())
    }

    private fun readRightSideText(filePath: String): String? {
        val virtualFile = findDiffVirtualFile(filePath) ?: return null
        val request = (virtualFile.chain.requests.firstOrNull()
            as? SimpleDiffRequestChain.DiffRequestProducerWrapper)?.request as? ContentDiffRequest
            ?: return null
        val rightContent = request.contents.getOrNull(1) as? DocumentContent ?: return null
        var text: String? = null
        ApplicationManager.getApplication().runReadAction { text = rightContent.document.text }
        return text
    }

    private fun closeDiffTab(filePath: String) {
        val virtualFile = findDiffVirtualFile(filePath) ?: return
        FileEditorManager.getInstance(project).closeFile(virtualFile)
    }

    private fun findDiffVirtualFile(filePath: String): ChainDiffVirtualFile? {
        for (file in FileEditorManager.getInstance(project).openFiles) {
            if (file !is ChainDiffVirtualFile) continue
            val producer = file.chain.requests.firstOrNull() as? SimpleDiffRequestChain.DiffRequestProducerWrapper
                ?: continue
            if (producer.request.getUserData(FILE_PATH_KEY) == filePath) return file
        }
        return null
    }

    private fun writeFile(path: String, text: String) {
        val resolved = CliqPathGuard.resolveInsideProject(project, path)
            ?: throw IOException("Refusing to write outside the project: $path")

        val ioFile = resolved.toFile()
        val parent = ioFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()

        val existing = LocalFileSystem.getInstance().findFileByPath(ioFile.path)
            ?: LocalFileSystem.getInstance().findFileByPath(path)
        if (existing != null) {
            WriteCommandAction.runWriteCommandAction(project, "Cliq: Apply Suggestion", null, {
                existing.setBinaryContent(text.toByteArray(StandardCharsets.UTF_8))
            })
            return
        }

        WriteAction.runAndWait<Throwable> {
            if (!ioFile.exists()) ioFile.createNewFile()
            val virtual = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(ioFile.path))
                ?: error("VFS could not resolve newly created file: $path")
            virtual.setBinaryContent(text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun onEdt(block: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            block()
        } else {
            application.invokeLater(block, ModalityState.any(), project.disposed)
        }
    }

    private fun notify(title: String, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CliqPlugin.NOTIFICATION_GROUP)
            .createNotification(title, message, type)
            .notify(project)
    }

    override fun dispose() {
        pendingFilePaths().forEach { path ->
            reviews.remove(path)
            val outcome = CliqDiffOutcome.Rejected(path)
            listeners.forEach { runCatching { it.onDiffOutcome(outcome) }.onFailure { log.warn(it) } }
        }
        listeners.clear()
        reviews.clear()
    }

    private data class ReviewState(
        val filePath: String,
        val proposedContent: String,
        val origin: Origin,
    )
}
