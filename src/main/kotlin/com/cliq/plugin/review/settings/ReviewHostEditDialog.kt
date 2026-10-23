package com.cliq.plugin.review.settings

import com.cliq.plugin.review.connect.HostUrls
import com.cliq.plugin.review.http.ConnectionOptions
import com.cliq.plugin.review.http.ReviewApiException
import com.cliq.plugin.review.provider.ProviderKind
import com.cliq.plugin.review.provider.ReviewProviders
import com.cliq.plugin.util.IndicatorCoroutines
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.util.Arrays
import javax.swing.JComponent
import javax.swing.SwingConstants

class ReviewHostEditDialog(
    project: Project?,
    private val store: ReviewHostStore,
    private val initial: ReviewHost?,
    suggestedUrl: String?,
    suggestedKind: ProviderKind?,
    private val existingUrls: Set<String>,
    private val connectionOptions: () -> ConnectionOptions,
    dialogTitle: String,
) : DialogWrapper(project, true) {

    class Result(
        val host: ReviewHost,
        val newToken: String?,
        val forgetToken: Boolean,
    )

    private companion object {
        const val TEST_TITLE = "Test Connection"
        const val HINT_WIDTH = 440
        const val HINT_HEIGHT = 56
        const val MALFORMED_TOKEN = "The token must be 8 to 512 printable characters without spaces."
    }

    private val urlField = JBTextField(initial?.url ?: suggestedUrl.orEmpty())
    private val kindCombo = ComboBox(ProviderKind.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.displayName }
        selectedItem = initial?.kind ?: suggestedKind ?: ProviderKind.guess(HostUrls.hostOf(urlField.text).orEmpty())
    }
    private val tokenField = JBPasswordField()
    private val tokenHint = JBLabel().apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        verticalAlignment = SwingConstants.TOP
        preferredSize = JBUI.size(HINT_WIDTH, HINT_HEIGHT)
        minimumSize = preferredSize
    }
    private val forgetTokenBox = JBCheckBox("Remove the stored token")

    init {
        title = dialogTitle
        isResizable = false
        kindCombo.addItemListener { updateTokenHint() }
        updateTokenHint()
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Host URL:") {
            cell(urlField).columns(COLUMNS_LARGE)
        }
        row {
            comment("Web address of the server, e.g. https://github.com or https://gitlab.example.com/gitlab", HINT_WIDTH / 6)
        }
        row("Provider:") {
            cell(kindCombo)
        }
        row("Token:") {
            cell(tokenField).columns(COLUMNS_LARGE)
        }
        row {
            cell(tokenHint)
        }
        if (initial?.tokenStored == true) {
            row {
                comment("A token is stored for this host. Leave the field empty to keep it.")
            }
            row {
                cell(forgetTokenBox)
            }
        }
        row {
            button(TEST_TITLE) { testConnection() }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (urlField.text.isBlank()) urlField else tokenField

    override fun doValidate(): ValidationInfo? {
        val url = HostUrls.normalize(urlField.text)
            ?: return ValidationInfo("Use an http(s) URL without credentials, query or fragment", urlField)
        if (url != initial?.url && url in existingUrls) return ValidationInfo("This host is already configured", urlField)
        val token = readTypedToken()
        if (token != null && !ReviewTokens.isWellFormed(token)) return ValidationInfo(MALFORMED_TOKEN, tokenField)
        return null
    }

    fun result(): Result? {
        val url = HostUrls.normalize(urlField.text) ?: return null
        val kind = selectedKind()
        val sameIdentity = initial != null && initial.url == url && initial.kind == kind
        val forget = forgetTokenBox.isSelected
        val newToken = readTypedToken()
        return Result(
            host = ReviewHost(url, kind, tokenStored = sameIdentity && initial?.tokenStored == true && !forget),
            newToken = newToken,
            forgetToken = forget,
        )
    }

    private fun selectedKind(): ProviderKind = kindCombo.selectedItem as? ProviderKind ?: ProviderKind.GITLAB

    private fun updateTokenHint() {
        tokenHint.text = "<html><body style='width: ${HINT_WIDTH - 10}px'>${selectedKind().tokenHint}</body></html>"
    }

    private fun testConnection() {
        val url = HostUrls.normalize(urlField.text)
        if (url == null) {
            Messages.showWarningDialog(urlField, "Enter a valid host URL first.", TEST_TITLE)
            return
        }
        val kind = selectedKind()
        val typedToken = readTypedToken()
        if (typedToken != null && !ReviewTokens.isWellFormed(typedToken)) {
            Messages.showErrorDialog(tokenField, MALFORMED_TOKEN, TEST_TITLE)
            return
        }
        val storedHost = initial?.takeIf { it.url == url && it.kind == kind && it.tokenStored && !forgetTokenBox.isSelected }
        if (typedToken == null && storedHost == null) {
            Messages.showWarningDialog(tokenField, "Enter a token to test the connection.", TEST_TITLE)
            return
        }
        val options = connectionOptions()
        try {
            val username = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<String, ReviewApiException> {
                    val token = typedToken ?: storedHost?.let(store::loadToken)
                        ?: throw ReviewApiException("The stored token could not be read")
                    val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
                    IndicatorCoroutines.runCancellable(indicator) {
                        ReviewProviders.of(kind).currentUser(url, token, options)
                    }
                },
                TEST_TITLE,
                true,
                null,
            )
            Messages.showInfoMessage(urlField, "Connected to ${kind.displayName} at $url as @$username.", TEST_TITLE)
        } catch (e: ProcessCanceledException) {
            return
        } catch (e: ReviewApiException) {
            Messages.showErrorDialog(urlField, e.message ?: "Request failed", TEST_TITLE)
        }
    }

    private fun readTypedToken(): String? {
        val chars = tokenField.password
        try {
            return String(chars).trim().takeIf { it.isNotEmpty() }
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }
}
