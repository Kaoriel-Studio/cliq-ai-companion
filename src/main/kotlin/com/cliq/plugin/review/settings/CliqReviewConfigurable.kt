package com.cliq.plugin.review.settings

import com.cliq.plugin.review.http.ConnectionOptions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.cliq.plugin.templates.CliqPromptTemplates
import com.cliq.plugin.templates.PromptTemplate
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.time.Duration
import javax.swing.JComponent
import javax.swing.ListSelectionModel

class CliqReviewConfigurable(private val project: Project) : BoundConfigurable("Code Review") {

    private companion object {
        const val HOSTS_TABLE_HEIGHT = 120
    }

    private val settings = CliqReviewSettings.getInstance()
    private val promptTemplates: List<PromptTemplate> = CliqPromptTemplates.getInstance().templates()
    private val draft = settings.snapshot()
    private val hostModel = ReviewHostTableModel()

    private lateinit var hostTable: JBTable
    private lateinit var ignoreProxyBox: JBCheckBox
    private lateinit var timeoutField: JBTextField
    private lateinit var excludedArea: JBTextArea
    private lateinit var promptArea: JBTextArea
    private lateinit var secretKeywordsArea: JBTextArea
    private lateinit var promptModeCombo: ComboBox<ReviewPromptMode>
    private lateinit var templateCombo: ComboBox<PromptTemplate>

    override fun createPanel(): DialogPanel = panel {
        group("Hosts") {
            row {
                checkBox("Keep hosts and tokens across projects").bindSelected(draft::shareHostsAcrossProjects)
            }
            row {
                comment(
                    "Off by default: hosts and tokens belong to this project only. " +
                        "On: every project sees the same hosts and tokens. Turning it on copies this project's hosts to the shared list.",
                )
            }
            row {
                cell(hostsPanel()).align(Align.FILL)
            }
            row {
                comment(
                    "Cliq sends each token only to the host it belongs to and never follows redirects with it. " +
                        "Hosts are matched against the project's git remote; GitHub Enterprise uses its web URL.",
                )
            }
        }

        group("Connection") {
            row {
                ignoreProxyBox = checkBox("Ignore local/system proxy for review requests")
                    .bindSelected(draft::ignoreProxy)
                    .component
            }
            row {
                comment("Connect directly, bypassing the IDE proxy settings and system proxies such as HTTP_PROXY and HTTPS_PROXY.")
            }
            row("Request timeout, seconds:") {
                timeoutField = intTextField(CliqReviewSettings.REQUEST_TIMEOUT_RANGE)
                    .bindIntText(draft::requestTimeoutSeconds)
                    .component
            }
        }

        group("Context Collection") {
            row("Chunk token budget:") {
                intTextField(CliqReviewSettings.CHUNK_TOKEN_BUDGET_RANGE, 1_000).bindIntText(draft::chunkTokenBudget)
            }
            row("Chunk line budget:") {
                intTextField(CliqReviewSettings.CHUNK_LINE_BUDGET_RANGE, 100).bindIntText(draft::chunkLineBudget)
            }
            row {
                comment("Keep chunks below what your agent reads in one go. Claude Code reads about 25k tokens or 2,000 lines per file.")
            }
            row("Diff context lines:") {
                intTextField(CliqReviewSettings.DIFF_CONTEXT_LINES_RANGE).bindIntText(draft::diffContextLines)
            }
            row("Max file size, KB:") {
                intTextField(CliqReviewSettings.MAX_FILE_SIZE_KB_RANGE).bindIntText(draft::maxFileSizeKb)
            }
            row("Related files per change:") {
                intTextField(CliqReviewSettings.RELATED_FILES_RANGE).bindIntText(draft::maxRelatedFilesPerChange)
            }
            row("Pre-change file content:") {
                comboBox(PreChangeContentMode.entries, SimpleListCellRenderer.create("") { it.displayName })
                    .bindItem(
                        { PreChangeContentMode.parse(draft.preChangeContent) },
                        { draft.preChangeContent = (it ?: PreChangeContentMode.REWRITES_ONLY).name },
                    )
            }
            row {
                checkBox("Include related test files").bindSelected(draft::includeRelatedTests)
            }
            row {
                checkBox("Include log tails of failed CI jobs").bindSelected(draft::includeFailedJobLogs)
            }
            row {
                checkBox("Fetch missing review commits with git").bindSelected(draft::fetchMissingCommits)
            }
            row {
                comment("Lets Cliq build wide-context diffs and read files locally instead of through the hosting API.")
            }
            row {
                checkBox("Add the .cliq directory to .gitignore").bindSelected(draft::gitIgnoreContextDirectory)
            }
            row {
                comment("Gemini CLI and Qwen Code skip git-ignored files referenced with @. Turn this off if you use them.")
            }
            row("Excluded patterns:") {}
            row {
                excludedArea = textArea().rows(8).bindText(draft::excludedPatterns).align(Align.FILL).component
            }.resizableRow()
            row {
                comment(
                    "One glob per line, matched against the file name or the path suffix; dir/* matches that directory at any depth. " +
                        "Secrets such as .env files, keys, certificates and credentials are always excluded.",
                )
            }
            row {
                link("Reset patterns to defaults") { excludedArea.text = CliqReviewSettings.DEFAULT_EXCLUDED_PATTERNS }
            }
        }

        group("After Collecting") {
            row("Prompt input:") {
                promptModeCombo = comboBox(ReviewPromptMode.entries, SimpleListCellRenderer.create("") { it.displayName })
                    .bindItem(
                        { ReviewPromptMode.parse(draft.promptMode) },
                        { draft.promptMode = (it ?: ReviewPromptMode.BUILT_IN).name },
                    )
                    .component
                promptModeCombo.addItemListener { updatePromptControls() }
            }
            row("Prompt template:") {
                templateCombo = comboBox(promptTemplates, SimpleListCellRenderer.create("") { it.title })
                    .bindItem(
                        { promptTemplates.firstOrNull { it.id == draft.promptTemplateId } ?: promptTemplates.firstOrNull() },
                        { draft.promptTemplateId = it?.id.orEmpty() },
                    )
                    .component
            }
            row {
                comment(
                    "Choose what lands in the chat input once the context is attached. Templates are managed in Settings | Tools | Cliq; " +
                        "they can use the placeholders below.",
                )
            }
            row("Review prompt:") {}
            row {
                promptArea = textArea().rows(10).bindText(draft::reviewPromptTemplate).align(Align.FILL).component
            }.resizableRow()
            row {
                comment("Placeholders: " + CliqReviewSettings.PLACEHOLDERS.joinToString(", ") { "{{$it}}" })
            }
            row {
                link("Reset prompt to default") { promptArea.text = CliqReviewSettings.DEFAULT_REVIEW_PROMPT_TEMPLATE }
            }
        }

        collapsibleGroup("Advanced") {
            row("Extra secret keywords:") {}
            row {
                secretKeywordsArea = textArea().rows(4).bindText(draft::secretKeywords).align(Align.FILL)
                    .validationOnApply { area ->
                        SecretKeywords.invalidEntries(area.text).takeIf { it.isNotEmpty() }?.let {
                            error("Use 2-40 letters, digits, '_', '-' or '.' per keyword: ${it.first().take(20)}")
                        }
                    }
                    .component
            }
            row {
                comment(
                    "One word per line, e.g. pass1 or pwd. Any name that contains the word is treated as a secret when it is assigned a value " +
                        "(pass1 = \"abc123\", DB_PWD: abc123), and the value is replaced with [REDACTED]. " +
                        "Built-in rules for passwords, tokens, keys and secret files always apply.",
                )
            }
        }
    }

    private fun updatePromptControls() {
        val mode = promptModeCombo.selectedItem as? ReviewPromptMode ?: ReviewPromptMode.BUILT_IN
        templateCombo.isEnabled = mode == ReviewPromptMode.TEMPLATE && promptTemplates.isNotEmpty()
        promptArea.isEnabled = mode == ReviewPromptMode.BUILT_IN
    }

    override fun isModified(): Boolean =
        super.isModified() ||
            hostModel.rows().any { it.isPending } ||
            hostModel.rows().map { it.original?.url to it.original?.kind } != currentHosts().map { it.url to it.kind }

    override fun apply() {
        super.apply()
        val store = ReviewHostStore.of(project)
        val wasShared = settings.shareHostsAcrossProjects
        val rows = hostModel.rows()
        val retained = rows.mapNotNull { it.original }.map { it.url to it.kind }.toSet()
        val removed = store.hosts().filter { (it.url to it.kind) !in retained }

        store.setHosts(
            rows.map { row ->
                row.host.copy(tokenStored = row.host.tokenStored || (row.identityChanged && row.original?.tokenStored == true))
            },
        )
        settings.update { state -> state.copyEditableFrom(draft) }
        val adoptIntoShared = !wasShared && settings.shareHostsAcrossProjects && !project.isDefault

        ApplicationManager.getApplication().executeOnPooledThread {
            removed.forEach(store::forgetToken)
            rows.forEach { applyTokenChange(store, it) }
            if (adoptIntoShared) store.adoptInto(ReviewHostStore(project, shared = true))
            ApplicationManager.getApplication().invokeLater(::refreshHostsIfUnchanged, ModalityState.any())
        }
        hostModel.replaceAll(currentHosts())
    }

    private fun currentHosts(): List<ReviewHost> = ReviewHostStore.of(project).hosts()

    private fun refreshHostsIfUnchanged() {
        if (hostModel.rows().none { it.isPending }) hostModel.replaceAll(currentHosts())
    }

    override fun reset() {
        draft.copyEditableFrom(settings.snapshot())
        super.reset()
        hostModel.replaceAll(currentHosts())
        if (::promptModeCombo.isInitialized) updatePromptControls()
    }

    private fun applyTokenChange(store: ReviewHostStore, row: ReviewHostRow) {
        val original = row.original
        when {
            row.newToken != null -> {
                if (original != null && row.identityChanged) store.forgetToken(original)
                store.storeToken(row.host, row.newToken)
            }
            row.forgetToken -> {
                original?.let(store::forgetToken)
                store.forgetToken(row.host)
            }
            original != null && row.identityChanged -> moveToken(store, original, row.host)
        }
    }

    private fun moveToken(store: ReviewHostStore, from: ReviewHost, to: ReviewHost) {
        val token = store.loadToken(from)
        store.forgetToken(from)
        if (token != null) store.storeToken(to, token) else store.markTokenStored(to.url, false)
    }

    private fun hostsPanel(): JComponent {
        hostTable = JBTable(hostModel).apply {
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            rowHeight = JBUI.scale(22)
            emptyText.text = "No hosts. Add GitLab or GitHub servers you collect reviews from."
        }
        return ToolbarDecorator.createDecorator(hostTable)
            .setAddAction { addHost() }
            .setEditAction { editSelectedHost() }
            .setRemoveAction { removeSelectedHost() }
            .setEditActionUpdater { hostTable.selectedRow >= 0 }
            .setRemoveActionUpdater { hostTable.selectedRow >= 0 }
            .disableUpDownActions()
            .setPreferredSize(JBUI.size(0, HOSTS_TABLE_HEIGHT))
            .createPanel()
    }

    private fun addHost() {
        val dialog = ReviewHostEditDialog(project, ReviewHostStore.of(project), null, null, null, hostModel.urls(), ::uiConnectionOptions, "Add Code Review Host")
        if (!dialog.showAndGet()) return
        val result = dialog.result() ?: return
        hostModel.add(ReviewHostRow(original = null, host = result.host, newToken = result.newToken))
    }

    private fun editSelectedHost() {
        val index = selectedIndex() ?: return
        val row = hostModel.rowAt(index)
        val current = row.host.copy(tokenStored = row.host.tokenStored && !row.forgetToken)
        val dialog = ReviewHostEditDialog(
            project, ReviewHostStore.of(project), current, null, null, hostModel.urls() - row.host.url, ::uiConnectionOptions, "Edit Code Review Host",
        )
        if (!dialog.showAndGet()) return
        val result = dialog.result() ?: return
        hostModel.update(
            index,
            ReviewHostRow(
                original = row.original,
                host = result.host,
                newToken = result.newToken ?: row.newToken.takeUnless { result.forgetToken },
                forgetToken = result.forgetToken || (row.forgetToken && result.newToken == null),
            ),
        )
    }

    private fun removeSelectedHost() {
        val index = selectedIndex() ?: return
        hostModel.remove(index)
    }

    private fun selectedIndex(): Int? {
        val view = hostTable.selectedRow
        if (view < 0) return null
        return hostTable.convertRowIndexToModel(view).takeIf { it >= 0 }
    }

    private fun uiConnectionOptions(): ConnectionOptions {
        val seconds = timeoutField.text.trim().toLongOrNull()
            ?.coerceIn(CliqReviewSettings.REQUEST_TIMEOUT_RANGE.first.toLong(), CliqReviewSettings.REQUEST_TIMEOUT_RANGE.last.toLong())
            ?: CliqReviewSettings.DEFAULT_REQUEST_TIMEOUT_SECONDS.toLong()
        return ConnectionOptions(Duration.ofSeconds(seconds), ignoreProxyBox.isSelected)
    }
}
