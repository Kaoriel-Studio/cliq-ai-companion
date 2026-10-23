package com.cliq.plugin.review.ui

import com.cliq.plugin.review.http.ReviewApiException
import com.cliq.plugin.review.model.ReviewRequestSummary
import com.cliq.plugin.review.provider.ProviderKind
import com.cliq.plugin.review.provider.ReviewProviders
import com.cliq.plugin.review.settings.CliqReviewSettings
import com.cliq.plugin.review.settings.ReviewHost
import com.cliq.plugin.review.settings.ReviewHostStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.event.ItemEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

class ReviewRequestPickerDialog(
    private val project: Project,
    private val store: ReviewHostStore,
    hosts: List<ReviewHost>,
    selectedHost: ReviewHost,
    initialProjectPath: String?,
    private val currentBranch: String?,
) : DialogWrapper(project) {

    class Selection(
        val host: ReviewHost,
        val projectReference: String,
        val number: Long,
    )

    private sealed interface LoadOutcome {
        class Loaded(val projectPath: String, val requests: List<ReviewRequestSummary>) : LoadOutcome
        class Failed(val message: String) : LoadOutcome
    }

    private companion object {
        const val MAX_LISTED_REQUESTS = 50
        const val MAX_NUMBER_DIGITS = 12
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostModel = CollectionComboBoxModel(hosts.toMutableList(), selectedHost)
    private val hostCombo = ComboBox(hostModel).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }
    private val projectField = JBTextField(initialProjectPath.orEmpty())
    private val numberLabel = JBLabel()
    private val numberField = JBTextField()
    private val statusLabel = JBLabel().apply { putClientProperty("html.disable", true) }
    private val listModel = CollectionListModel<ReviewRequestSummary>()
    private val list = JBList(listModel)
    private var loadJob: Job? = null

    init {
        title = "Collect Review Context"
        setOKButtonText("Collect")
        configureList()
        hostCombo.addItemListener { event -> if (event.stateChange == ItemEvent.SELECTED) onHostChanged() }
        updateKindLabels()
        init()
        Disposer.register(disposable) { scope.cancel() }
        reload()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Host:") {
            cell(hostCombo).align(AlignX.FILL).resizableColumn()
            link("Add host…") { addHost() }
        }
        row("Project:") {
            cell(projectField).align(AlignX.FILL).resizableColumn()
            link("Reload") { reload() }
        }
        row {
            comment("GitLab: group/subgroup/project or a numeric ID. GitHub: owner/repository.")
        }
        row { cell(statusLabel) }
        row {
            cell(JBScrollPane(list)).align(Align.FILL)
        }.resizableRow()
        row {
            cell(numberLabel)
            cell(numberField).columns(12).focused()
        }
    }.apply {
        preferredSize = JBUI.size(700, 460)
    }

    override fun getPreferredFocusedComponent(): JComponent = numberField

    override fun doValidate(): ValidationInfo? {
        val host = selectedHost() ?: return ValidationInfo("Choose a host", hostCombo)
        if (ReviewProviders.of(host.kind).normalizeProjectReference(projectField.text) == null) {
            return ValidationInfo(projectHint(host.kind), projectField)
        }
        if (parseNumber(numberField.text) == null) {
            return ValidationInfo("Enter the ${host.kind.requestNoun} number", numberField)
        }
        return null
    }

    fun selection(): Selection? {
        val host = selectedHost() ?: return null
        val reference = ReviewProviders.of(host.kind).normalizeProjectReference(projectField.text) ?: return null
        val number = parseNumber(numberField.text) ?: return null
        return Selection(host, reference, number)
    }

    private fun selectedHost(): ReviewHost? = hostCombo.selectedItem as? ReviewHost

    private fun addHost() {
        val added = CollectReviewContextAction.addHost(project, store, null) ?: return
        if (isDisposed) return
        if (hostModel.items.none { it.url == added.url }) hostModel.add(added)
        hostModel.selectedItem = hostModel.items.first { it.url == added.url }
    }

    private fun onHostChanged() {
        updateKindLabels()
        reload()
    }

    private fun updateKindLabels() {
        val kind = selectedHost()?.kind ?: ProviderKind.GITLAB
        numberLabel.text = kind.numberLabel
        list.emptyText.text = "No open ${kind.requestNoun}s loaded"
    }

    private fun configureList() {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = RequestCellRenderer(currentBranch) { selectedHost()?.kind ?: ProviderKind.GITLAB }
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) list.selectedValue?.let { numberField.text = it.number.toString() }
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (list.selectedValue == null) return false
                doOKAction()
                return true
            }
        }.installOn(list)
    }

    private fun reload() {
        val host = selectedHost() ?: return
        val reference = ReviewProviders.of(host.kind).normalizeProjectReference(projectField.text)
        loadJob?.cancel()
        listModel.removeAll()
        if (reference == null) {
            showStatus(projectHint(host.kind), isError = false)
            return
        }
        showStatus("Loading open ${host.kind.requestNoun}s…", isError = false)
        loadJob = scope.launch {
            val outcome = load(host, reference)
            ApplicationManager.getApplication().invokeLater({ apply(host, outcome) }, ModalityState.any())
        }
    }

    private suspend fun load(host: ReviewHost, reference: String): LoadOutcome {
        val settings = CliqReviewSettings.getInstance()
        val current = store.host(host.url) ?: host
        val token = store.loadToken(current)
        return try {
            ReviewProviders.of(host.kind).connect(host.url, reference, token, settings.connectionOptions()).use { session ->
                LoadOutcome.Loaded(session.project.path, session.openRequests(MAX_LISTED_REQUESTS))
            }
        } catch (e: ReviewApiException) {
            LoadOutcome.Failed(e.message ?: "${host.kind.displayName} request failed")
        }
    }

    private fun apply(host: ReviewHost, outcome: LoadOutcome) {
        if (isDisposed || selectedHost() != host) return
        when (outcome) {
            is LoadOutcome.Failed -> showStatus(outcome.message, isError = true)
            is LoadOutcome.Loaded -> {
                val ordered = outcome.requests.sortedByDescending { it.sourceBranch == currentBranch }
                listModel.replaceAll(ordered)
                showStatus("${ordered.size} open ${host.kind.requestNoun}(s) in ${outcome.projectPath}", isError = false)
                if (numberField.text.isBlank()) {
                    ordered.firstOrNull { it.sourceBranch == currentBranch }?.let { list.setSelectedValue(it, true) }
                }
            }
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusLabel.text = ReviewPrompt.singleLine(message)
        statusLabel.foreground = if (isError) JBColor.RED else JBColor.foreground()
    }

    private fun projectHint(kind: ProviderKind): String = when (kind) {
        ProviderKind.GITLAB -> "Enter a project path like group/project or a numeric project ID"
        ProviderKind.GITHUB -> "Enter a repository like owner/repository"
    }

    private fun parseNumber(raw: String): Long? {
        val digits = raw.trim().trimStart('!', '#')
        if (digits.isEmpty() || digits.length > MAX_NUMBER_DIGITS || !digits.all(Char::isDigit)) return null
        return digits.toLong().takeIf { it > 0 }
    }

    private class RequestCellRenderer(
        private val currentBranch: String?,
        private val kind: () -> ProviderKind,
    ) : ColoredListCellRenderer<ReviewRequestSummary>() {
        override fun customizeCellRenderer(
            list: JList<out ReviewRequestSummary>,
            value: ReviewRequestSummary?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            append("${kind().reference(value.number)}  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (value.draft) append("Draft: ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(ReviewPrompt.singleLine(value.title))
            append(
                "  ${ReviewPrompt.singleLine(value.sourceBranch)} → ${ReviewPrompt.singleLine(value.targetBranch)}",
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
            value.author?.let { append("  @${ReviewPrompt.singleLine(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            if (currentBranch != null && value.sourceBranch == currentBranch) {
                append("  current branch", SimpleTextAttributes.LINK_BOLD_ATTRIBUTES)
            }
        }
    }
}
