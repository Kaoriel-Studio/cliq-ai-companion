package com.cliq.plugin.toolwindow

import com.cliq.plugin.CliqPlugin
import com.cliq.plugin.agents.CliAgentDefinition
import com.cliq.plugin.diff.CliqDiffManager
import com.cliq.plugin.review.ui.CollectReviewContextAction
import com.cliq.plugin.history.CliqPromptHistory
import com.cliq.plugin.history.PromptHistoryEntry
import com.cliq.plugin.settings.CliqSettings
import com.cliq.plugin.templates.CliqPromptTemplates
import com.cliq.plugin.templates.PromptTemplate
import com.cliq.plugin.templates.PromptTemplateEditDialog
import com.cliq.plugin.templates.PromptTemplateManagerDialog
import com.cliq.plugin.templates.PromptTemplateVariables
import com.cliq.plugin.terminal.CliqTerminalLauncher
import com.cliq.plugin.terminal.TerminalTyper
import com.cliq.plugin.ui.CliqButton
import com.cliq.plugin.ui.CliqTheme
import com.cliq.plugin.util.CliPathEscaper
import com.cliq.plugin.util.PathUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.DataFlavor
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ToolTipManager

class CliqToolWindowPanel(private val project: Project) : JBPanel<CliqToolWindowPanel>(BorderLayout()), Disposable, UiDataProvider {

    interface InsertPromptTextListener : java.util.EventListener {
        fun onInsertPromptTextRequested(text: String)
    }

    companion object {
        val INSERT_PROMPT_TOPIC: com.intellij.util.messages.Topic<InsertPromptTextListener> =
            com.intellij.util.messages.Topic.create("Cliq Insert Prompt Text", InsertPromptTextListener::class.java)

        const val MAX_RECENT_FILES = 20
        const val MAX_PINNED_FILES = 50
    }

    private val connection = project.messageBus.connect(this)

    private val recentFilesModel = CollectionListModel<VirtualFile>()
    private val pinnedFilesModel = CollectionListModel<VirtualFile>()

    private val recentList = JBList(recentFilesModel)
    private val pinnedList = JBList(pinnedFilesModel)

    private lateinit var promptArea: JBTextArea
    private lateinit var contextCheckBox: JBCheckBox
    private var clipboardTainted = false

    override fun dispose() {
        ToolTipManager.sharedInstance().unregisterComponent(recentList)
        ToolTipManager.sharedInstance().unregisterComponent(pinnedList)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        val selected = mutableListOf<VirtualFile>()
        selected.addAll(recentList.selectedValuesList)
        selected.addAll(pinnedList.selectedValuesList)
        if (selected.isNotEmpty()) {
            sink[CommonDataKeys.VIRTUAL_FILE_ARRAY] = selected.toTypedArray()
        }
        sink[CommonDataKeys.VIRTUAL_FILE] = pinnedList.selectedValue ?: recentList.selectedValue
    }

    init {
        border = JBUI.Borders.empty(12)

        val launcher = project.service<CliqTerminalLauncher>()
        val basePath = project.basePath

        ToolTipManager.sharedInstance().registerComponent(recentList)
        ToolTipManager.sharedInstance().registerComponent(pinnedList)

        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                val file = event.newFile ?: return
                if (recentFilesModel.items.none { it.path == file.path }) {
                    recentFilesModel.add(0, file)
                    while (recentFilesModel.size > MAX_RECENT_FILES) {
                        recentFilesModel.remove(recentFilesModel.size - 1)
                    }
                }
            }
        })

        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.isEmpty()) return
                dropInvalidFiles(recentFilesModel)
                dropInvalidFiles(pinnedFilesModel)
                recentList.repaint()
                pinnedList.repaint()
            }
        })

        val toolbar = buildTopToolbar(launcher)

        val dropZone = FilesDropZone { dropped -> dropped.forEach(::pinFile) }

        val filesSplitter = com.intellij.ui.JBSplitter(false, 0.5f).apply {
            firstComponent = buildFilesSection("Recently opened", recentList, basePath, isPinned = false)
            secondComponent = buildFilesSection("Context", pinnedList, basePath, isPinned = true)
        }

        dropZone.add(filesSplitter, BorderLayout.CENTER)

        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(dropZone, BorderLayout.CENTER)
            add(buildPendingChangesSection(basePath), BorderLayout.SOUTH)
        }

        val mainSplitter = com.intellij.ui.JBSplitter(true, 0.6f).apply {
            setHonorComponentsMinimumSize(true)
            firstComponent = topPanel
            secondComponent = buildPromptPanel(basePath) {
                FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            }
        }

        add(toolbar, BorderLayout.NORTH)
        add(mainSplitter, BorderLayout.CENTER)

        project.service<CliqPendingPrompt>().consume()?.let(::applyPromptText)

        connection.subscribe(CliqContextAttachments.TOPIC, CliqContextAttachments.Listener { consumeAttachment() })
        consumeAttachment()
    }

    private fun consumeAttachment() {
        val attachment = project.service<CliqContextAttachments>().consume() ?: return
        ApplicationManager.getApplication().invokeLater(
            { applyAttachment(attachment) },
            ModalityState.any(),
            project.disposed,
        )
    }

    private fun applyAttachment(attachment: CliqContextAttachments.Attachment) {
        attachment.replacedDirectory?.let { directory ->
            val prefix = directory.trimEnd('/') + "/"
            pinnedFilesModel.items.filter { it.path.startsWith(prefix) }.forEach(pinnedFilesModel::remove)
        }
        attachment.files.filter { it.isValid }.forEach(::pinFile)
        contextCheckBox.isSelected = true
        attachment.prompt?.let {
            promptArea.text = it
            promptArea.requestFocusInWindow()
        }
    }

    private fun dropInvalidFiles(model: CollectionListModel<VirtualFile>) {
        model.items.filter { !it.isValid }.forEach(model::remove)
    }

    private fun pinFile(file: VirtualFile) {
        if (pinnedFilesModel.items.any { it.path == file.path }) return
        pinnedFilesModel.add(file)
        while (pinnedFilesModel.size > MAX_PINNED_FILES) {
            pinnedFilesModel.remove(0)
        }
    }

    private fun applyPromptText(text: String) {
        ApplicationManager.getApplication().invokeLater(
            {
                promptArea.text = text
                promptArea.requestFocusInWindow()
            },
            ModalityState.any(),
            project.disposed,
        )
    }

    private fun buildFilesSection(
        title: String,
        list: JBList<VirtualFile>,
        basePath: String?,
        isPinned: Boolean,
    ): JPanel {
        val model = list.model as CollectionListModel<VirtualFile>
        val zones = CliqFileCellRenderer.actionZones()

        list.apply {
            cellRenderer = CliqFileCellRenderer(basePath)
            emptyText.text = if (isPinned) "Drag files here" else "No recent files"
            background = CliqTheme.SURFACE

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index == -1 || index != selectedIndex) return
                    val bounds = getCellBounds(index, index) ?: return
                    val file = model.getElementAt(index)

                    when (zones.hitIndex(bounds.width, e.x)) {
                        CliqFileCellRenderer.ACTION_DELETE -> model.remove(file)
                        CliqFileCellRenderer.ACTION_SEND -> {
                            val relative = PathUtil.toRelativePosix(basePath, file.path) ?: return
                            TerminalTyper.typeInActiveTerminal(project, " @${CliPathEscaper.escape(relative)} ")
                        }
                    }
                }
            })
        }

        val scroll = com.intellij.ui.components.JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
        }

        val headerActions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }

        headerActions.add(iconLabel(AllIcons.Actions.MoveTo2, "Send all to terminal") {
            val tokens = model.items.mapNotNull { PathUtil.toRelativePosix(basePath, it.path) }
            if (tokens.isEmpty()) return@iconLabel
            val payload = tokens.joinToString(" ") { "@${CliPathEscaper.escape(it)}" }
            TerminalTyper.typeInActiveTerminal(project, " $payload ")
        })

        if (isPinned) {
            headerActions.add(iconLabel(AllIcons.General.Add, "Add files") { chooseAndAddFiles() })
        }

        headerActions.add(
            iconLabel(AllIcons.Actions.GC, if (isPinned) "Clear context" else "Clear recent files") {
                if (model.size > 0) model.removeAll()
            }
        )

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
            add(
                JBLabel(title).apply { font = CliqTheme.captionFont(font).deriveFont(Font.BOLD) },
                BorderLayout.WEST,
            )
            add(headerActions, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
            add(header, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
    }

    private fun buildPendingChangesSection(basePath: String?): JPanel {
        val diffManager = project.service<CliqDiffManager>()
        val model = CollectionListModel<String>()
        val zones = CliqPendingChangeCellRenderer.actionZones()

        val list = JBList(model).apply {
            cellRenderer = CliqPendingChangeCellRenderer(basePath)
            background = CliqTheme.SURFACE
            emptyText.text = "No pending changes"

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index == -1) return
                    val filePath = model.getElementAt(index)

                    if (index != selectedIndex) {
                        diffManager.focusDiff(filePath)
                        return
                    }

                    val bounds = getCellBounds(index, index) ?: return
                    when (zones.hitIndex(bounds.width, e.x)) {
                        CliqPendingChangeCellRenderer.ACTION_REJECT -> {
                            if (confirm("Reject changes", "Discard Cliq's proposed changes to this file?")) {
                                diffManager.reject(filePath)
                            }
                        }
                        CliqPendingChangeCellRenderer.ACTION_ACCEPT -> diffManager.accept(filePath)
                        else -> diffManager.focusDiff(filePath)
                    }
                }
            })
        }
        ToolTipManager.sharedInstance().registerComponent(list)

        val scroll = com.intellij.ui.components.JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
        }

        val headerActions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(iconLabel(AllIcons.Actions.Checked, "Accept all pending changes") {
                val count = diffManager.pendingFilePaths().size
                if (count == 0) return@iconLabel
                if (confirm("Accept all changes", "Apply Cliq's proposed changes to $count file(s)?")) {
                    diffManager.acceptAll()
                }
            })
            add(iconLabel(AllIcons.Actions.Cancel, "Reject all pending changes") {
                val count = diffManager.pendingFilePaths().size
                if (count == 0) return@iconLabel
                if (confirm("Reject all changes", "Discard Cliq's proposed changes to $count file(s)?")) {
                    diffManager.rejectAll()
                }
            })
        }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
            add(
                JBLabel("Pending Changes").apply { font = CliqTheme.captionFont(font).deriveFont(Font.BOLD) },
                BorderLayout.WEST,
            )
            add(headerActions, BorderLayout.EAST)
        }

        val container = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 8, 0, 8)
            isVisible = false
            preferredSize = Dimension(0, 140)
            add(header, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }

        fun refresh(paths: List<String>) {
            model.removeAll()
            paths.forEach { model.add(it) }
            container.isVisible = paths.isNotEmpty()
            container.revalidate()
            container.repaint()
        }

        refresh(diffManager.pendingFilePaths())

        connection.subscribe(CliqDiffManager.PENDING_TOPIC, object : CliqDiffManager.PendingReviewsListener {
            override fun onPendingReviewsChanged(pendingFilePaths: List<String>) {
                ApplicationManager.getApplication().invokeLater(
                    { refresh(pendingFilePaths) },
                    ModalityState.any(),
                    project.disposed,
                )
            }
        })

        return container
    }

    private fun chooseAndAddFiles() {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withTitle("Select Files for Context")
        FileChooser.chooseFiles(descriptor, project, null).forEach(::pinFile)
    }

    private fun buildTopToolbar(launcher: CliqTerminalLauncher): JPanel {
        val comboModel = DefaultComboBoxModel(CliqSettings.getInstance().agents().toTypedArray())

        val agentCombo = com.intellij.openapi.ui.ComboBox(comboModel).apply {
            isOpaque = false
            renderer = SimpleListCellRenderer.create("") { it?.displayName }
        }

        connection.subscribe(CliqSettings.TOPIC, object : CliqSettings.AgentsListener {
            override fun onAgentsChanged(agents: List<CliAgentDefinition>) {
                ApplicationManager.getApplication().invokeLater(
                    {
                        val previousId = (agentCombo.selectedItem as? CliAgentDefinition)?.id
                        comboModel.removeAllElements()
                        agents.forEach { comboModel.addElement(it) }
                        comboModel.selectedItem = agents.firstOrNull { it.id == previousId } ?: agents.firstOrNull()
                    },
                    ModalityState.any(),
                    project.disposed,
                )
            }
        })

        val startBtn = CliqButton("Start", CliqButton.Variant.GHOST).apply {
            addActionListener {
                val selectedAgent = agentCombo.selectedItem as? CliAgentDefinition ?: return@addActionListener
                launcher.launch(selectedAgent)
            }
        }

        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(12)

            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = JBUI.insetsRight(8)
            }

            add(agentCombo, gbc)

            gbc.weightx = 0.0
            gbc.insets = JBUI.emptyInsets()
            add(startBtn, gbc)
        }
    }

    private fun buildPromptPanel(basePath: String?, getActiveFile: () -> VirtualFile?): JPanel {
        val container = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.emptyTop(8)
            isOpaque = false
        }

        val cbActive = com.intellij.ui.components.JBCheckBox("Active file", false).apply {
            isOpaque = false
            foreground = CliqTheme.PRIMARY_TEXT
        }
        val cbContext = JBCheckBox("Context", false).apply {
            isOpaque = false
            foreground = CliqTheme.PRIMARY_TEXT
        }.also { contextCheckBox = it }
        val cbRecent = com.intellij.ui.components.JBCheckBox("Recently opened", false).apply {
            isOpaque = false
            foreground = CliqTheme.PRIMARY_TEXT
        }

        val checksPanel = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            isOpaque = false
            add(cbActive)
            add(cbContext)
            add(cbRecent)
        }

        promptArea = JBTextArea().apply {
            emptyText.text = "Ask Cliq... (Enter to send, Shift+Enter for new line)"
            lineWrap = true
            wrapStyleWord = true
            rows = 3
            isOpaque = false
            background = null
            foreground = CliqTheme.PRIMARY_TEXT
            font = CliqTheme.captionFont(font)
            border = JBUI.Borders.empty(8, 12)
        }

        connection.subscribe(INSERT_PROMPT_TOPIC, object : InsertPromptTextListener {
            override fun onInsertPromptTextRequested(text: String) {
                project.service<CliqPendingPrompt>().consume()
                applyPromptText(text)
            }
        })

        val inputWrapper = JPanel(BorderLayout()).apply {
            background = CliqTheme.INPUT_BG
            isOpaque = true
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CliqTheme.SURFACE_BORDER, 1, true),
                JBUI.Borders.empty(2),
            )

            val scrollPane = com.intellij.ui.components.JBScrollPane(promptArea).apply {
                border = BorderFactory.createEmptyBorder()
                isOpaque = false
                viewport.isOpaque = false
                viewport.background = null
            }

            val historyIcon = iconLabel(AllIcons.Vcs.History, "Insert a previously sent prompt")
            val templateIcon = iconLabel(AllIcons.Actions.ListFiles, "Insert prompt template")
            val saveTemplateIcon = iconLabel(AllIcons.Actions.MenuSaveall, "Save current input as prompt template")
            val mergeRequestIcon = iconLabel(AllIcons.Vcs.Merge, "Collect pull/merge request context for review")
            val sendIcon = iconLabel(AllIcons.Actions.Execute, "Send prompt")

            val actionIconsRow = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 0, 2, 4)
                add(historyIcon)
                add(templateIcon)
                add(saveTemplateIcon)
                add(mergeRequestIcon)
                add(sendIcon)
            }

            add(scrollPane, BorderLayout.CENTER)
            add(actionIconsRow, BorderLayout.SOUTH)

            fun sendPrompt() {
                val userText = promptArea.text.trim()
                if (userText.isEmpty()) return

                val tokensToAttach = linkedSetOf<String>()

                if (cbActive.isSelected) {
                    getActiveFile()?.let { file ->
                        PathUtil.toRelativePosix(basePath, file.path)?.let(tokensToAttach::add)
                    }
                }
                if (cbContext.isSelected) {
                    pinnedFilesModel.items.forEach { file ->
                        PathUtil.toRelativePosix(basePath, file.path)?.let(tokensToAttach::add)
                    }
                }
                if (cbRecent.isSelected) {
                    recentFilesModel.items.forEach { file ->
                        PathUtil.toRelativePosix(basePath, file.path)?.let(tokensToAttach::add)
                    }
                }

                val contextString = tokensToAttach.joinToString(" ") { "@${CliPathEscaper.escape(it)}" }
                val payload = buildString {
                    if (contextString.isNotEmpty()) {
                        append(contextString)
                        append(" \n\n")
                    }
                    append(userText)
                }

                if (!clipboardTainted) {
                    project.service<CliqPromptHistory>().record(userText)
                }
                clipboardTainted = false

                TerminalTyper.typeInActiveTerminal(project, payload, execute = true)
                promptArea.text = ""
            }

            onClick(historyIcon) { showHistoryPopup(historyIcon) }
            onClick(templateIcon) { showTemplatePopup(templateIcon, basePath, getActiveFile) }
            onClick(saveTemplateIcon) { saveCurrentInputAsTemplate() }
            onClick(mergeRequestIcon) { CollectReviewContextAction.start(project) }
            onClick(sendIcon) { sendPrompt() }

            promptArea.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode != java.awt.event.KeyEvent.VK_ENTER) return
                    if (e.isShiftDown) {
                        e.consume()
                        promptArea.replaceSelection("\n")
                        return
                    }
                    e.consume()
                    sendPrompt()
                }
            })
        }

        container.add(checksPanel, BorderLayout.NORTH)
        container.add(inputWrapper, BorderLayout.CENTER)

        return container
    }

    private fun insertTextIntoPrompt(text: String) {
        promptArea.replaceSelection(text)
        promptArea.requestFocusInWindow()
    }

    private fun showHistoryPopup(anchor: JLabel) {
        val history = project.service<CliqPromptHistory>().entries()
        val group = DefaultActionGroup()

        if (history.isEmpty()) {
            group.add(
                textAction("No sent prompts yet", null, null) {}
                    .apply { templatePresentation.isEnabled = false }
            )
        } else {
            history.forEach { entry ->
                group.add(textAction(historyLabel(entry), null, null) { insertTextIntoPrompt(entry.text) })
            }
            group.addSeparator()
            group.add(textAction("Clear history", null, AllIcons.Actions.GC) {
                if (confirm("Clear prompt history", "Delete all ${history.size} stored prompts for this project?")) {
                    project.service<CliqPromptHistory>().clear()
                }
            })
        }

        showPopup("Prompt History", group, anchor)
    }

    private fun historyLabel(entry: PromptHistoryEntry): String {
        val firstLine = entry.text.lineSequence().firstOrNull().orEmpty()
        return if (firstLine.length > 80) firstLine.take(80) + "…" else firstLine
    }

    private fun showTemplatePopup(anchor: JLabel, basePath: String?, getActiveFile: () -> VirtualFile?) {
        val templates = CliqPromptTemplates.getInstance().templates()
        val group = DefaultActionGroup()

        if (templates.isEmpty()) {
            group.add(
                textAction("No templates yet", null, null) {}
                    .apply { templatePresentation.isEnabled = false }
            )
        } else {
            templates.forEach { template ->
                group.add(
                    textAction(template.title, template.description.ifBlank { null }, null) {
                        insertTemplate(template, basePath, getActiveFile)
                    }
                )
            }
        }

        group.addSeparator()
        group.add(textAction("Manage templates…", null, AllIcons.General.Settings) {
            PromptTemplateManagerDialog().show()
        })

        showPopup("Insert Prompt Template", group, anchor)
    }

    private fun insertTemplate(template: PromptTemplate, basePath: String?, getActiveFile: () -> VirtualFile?) {
        val activeFileValue = getActiveFile()?.let { PathUtil.toRelativePosix(basePath, it.path) }
        val selectionValue = FileEditorManager.getInstance(project)
            .selectedTextEditor?.selectionModel?.selectedText

        if (!PromptTemplateVariables.uses(template.content, PromptTemplateVariables.CLIPBOARD)) {
            finishTemplateInsert(template, activeFileValue, selectionValue, null)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val clipboard = runCatching {
                CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
            }.getOrNull()

            ApplicationManager.getApplication().invokeLater(
                {
                    if (!confirmClipboardUsage(clipboard)) return@invokeLater
                    if (!clipboard.isNullOrEmpty()) clipboardTainted = true
                    finishTemplateInsert(template, activeFileValue, selectionValue, clipboard)
                },
                ModalityState.any(),
                project.disposed,
            )
        }
    }

    private fun confirmClipboardUsage(clipboard: String?): Boolean {
        if (!CliqSettings.getInstance().confirmClipboardPlaceholder) return true

        val preview = when {
            clipboard.isNullOrEmpty() -> "(clipboard is empty)"
            clipboard.length > 300 -> clipboard.take(300) + "…"
            else -> clipboard
        }

        val approved = MessageDialogBuilder
            .yesNo(
                "Insert clipboard content?",
                "This template inserts your clipboard into the prompt:\n\n$preview",
            )
            .yesText("Insert")
            .noText("Cancel")
            .ask(project)

        if (approved) {
            val remember = MessageDialogBuilder
                .yesNo("Remember choice", "Insert clipboard content without asking next time?")
                .yesText("Don't ask again")
                .noText("Keep asking")
                .ask(project)
            if (remember) CliqSettings.getInstance().confirmClipboardPlaceholder = false
        }
        return approved
    }

    private fun finishTemplateInsert(
        template: PromptTemplate,
        activeFile: String?,
        selection: String?,
        clipboard: String?,
    ) {
        val missing = PromptTemplateVariables.unresolved(template.content, activeFile, selection, clipboard)
        insertTextIntoPrompt(
            PromptTemplateVariables.resolve(template.content, activeFile, selection, clipboard)
        )
        if (missing.isNotEmpty()) {
            notify(
                "Template inserted with empty placeholders",
                "Nothing was available for: ${missing.joinToString(", ") { "{{$it}}" }}.",
                NotificationType.INFORMATION,
            )
        }
    }

    private fun saveCurrentInputAsTemplate() {
        val text = promptArea.text
        if (text.isBlank()) return
        val existingTitles = CliqPromptTemplates.getInstance().templates().map { it.title }.toSet()
        val dialog = PromptTemplateEditDialog(PromptTemplate(content = text), existingTitles)
        if (dialog.showAndGet()) {
            CliqPromptTemplates.getInstance().addTemplate(dialog.buildResult())
        }
    }

    private fun showPopup(title: String, group: DefaultActionGroup, anchor: JLabel) {
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                title,
                group,
                DataManager.getInstance().getDataContext(anchor),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
            )
            .showUnderneathOf(anchor)
    }

    private fun textAction(label: String, description: String?, icon: Icon?, onPerform: () -> Unit): AnAction =
        object : AnAction() {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = onPerform()
        }.apply {
            templatePresentation.setText(label, false)
            templatePresentation.description = description
            templatePresentation.icon = icon
        }

    private fun iconLabel(icon: Icon, tooltip: String): JLabel =
        JLabel(icon).apply {
            this.toolTipText = tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(0, 4)
        }

    private fun iconLabel(icon: Icon, tooltip: String, onPerform: () -> Unit): JLabel =
        iconLabel(icon, tooltip).apply { onClick(this, onPerform) }

    private fun onClick(component: JLabel, onPerform: () -> Unit) {
        component.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = onPerform()
        })
    }

    private fun confirm(title: String, message: String): Boolean =
        MessageDialogBuilder.yesNo(title, message).ask(project)

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(CliqPlugin.NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }
}
