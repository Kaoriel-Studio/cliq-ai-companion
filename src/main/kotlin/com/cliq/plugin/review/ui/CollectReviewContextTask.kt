package com.cliq.plugin.review.ui

import com.cliq.plugin.review.collect.CollectionProgress
import com.cliq.plugin.review.collect.LocalCommitPreparer
import com.cliq.plugin.review.collect.ReviewContextCollector
import com.cliq.plugin.review.collect.diff.IdeLineDiffEngine
import com.cliq.plugin.review.collect.filter.SecretRedactor
import com.cliq.plugin.review.collect.output.ReviewContextStorage
import com.cliq.plugin.review.collect.related.IdeRelatedFileIndex
import com.cliq.plugin.review.collect.render.RenderedContext
import com.cliq.plugin.review.collect.render.ReviewContextRenderer
import com.cliq.plugin.review.collect.source.LocalGitReader
import com.cliq.plugin.review.connect.ReviewTargetResolver
import com.cliq.plugin.review.http.ReviewApiException
import com.cliq.plugin.review.model.ReviewContextException
import com.cliq.plugin.review.provider.ReviewProviders
import com.cliq.plugin.review.settings.CliqReviewSettings
import com.cliq.plugin.review.settings.ReviewHostStore
import com.cliq.plugin.review.settings.ReviewPromptMode
import com.cliq.plugin.review.settings.ReviewCollectorOptions
import com.cliq.plugin.templates.CliqPromptTemplates
import com.cliq.plugin.toolwindow.CliqContextAttachments
import com.cliq.plugin.util.IndicatorCoroutines
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.nio.file.Path

class CollectReviewContextTask(
    project: Project,
    private val selection: ReviewRequestPickerDialog.Selection,
) : Task.Backgroundable(
    project,
    "Cliq: collecting ${selection.host.kind.requestNoun} ${selection.host.kind.reference(selection.number)}",
    true,
) {

    private companion object {
        const val METADATA_FRACTION = 0.1
        const val LOCAL_GIT_FRACTION = 0.15
        const val CONTENT_START_FRACTION = 0.2
        const val CONTENT_SPAN_FRACTION = 0.65
        const val RENDER_FRACTION = 0.9
        const val WRITE_FRACTION = 0.95
        const val TOKENS_PER_THOUSAND = 1000
    }

    private class Outcome(
        val rendered: RenderedContext,
        val files: List<VirtualFile>,
        val directory: Path,
        val prompt: String?,
        val redactions: Int,
        val warnings: Int,
        val title: String,
    )

    private class IndicatorProgress(private val indicator: ProgressIndicator) : CollectionProgress {
        override fun phase(text: String) {
            indicator.text = text
            indicator.text2 = ""
        }

        override fun advance(done: Int, total: Int) {
            if (total <= 0) return
            indicator.fraction = CONTENT_START_FRACTION + CONTENT_SPAN_FRACTION * done / total
            indicator.text2 = "$done of $total files"
        }
    }

    private val log = logger<CollectReviewContextTask>()
    private val kind = selection.host.kind
    private val reference = kind.reference(selection.number)

    @Volatile
    private var outcome: Outcome? = null

    override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = false
        val basePath = project.basePath ?: throw ReviewContextException("The project has no base directory")
        val settings = CliqReviewSettings.getInstance()
        val store = ReviewHostStore.of(project)
        val host = store.host(selection.host.url) ?: selection.host
        val token = store.loadToken(host)
        val options = settings.collectorOptions()
        val redactor = SecretRedactor(listOfNotNull(token), options.secretKeywords)
        val progress = IndicatorProgress(indicator)

        indicator.text = "Connecting to ${kind.displayName}"
        val session = IndicatorCoroutines.runCancellable(indicator) {
            ReviewProviders.of(kind).connect(host.url, selection.projectReference, token, settings.connectionOptions())
        }
        session.use {
            val collector = ReviewContextCollector(session, options, redactor, IdeLineDiffEngine, progress)
            val metadata = IndicatorCoroutines.runCancellable(indicator) { collector.fetchMetadata(selection.number) }
            indicator.fraction = METADATA_FRACTION

            progress.phase("Checking local git history")
            val match = ReviewTargetResolver.match(ReviewTargetResolver.localRepositories(project), session.project.path)
            val localRepository = LocalCommitPreparer.prepare(
                match,
                metadata,
                session.fetchRefspec(selection.number),
                options.fetchMissingCommits,
                collector.diagnostics,
            )
            indicator.checkCanceled()
            indicator.fraction = LOCAL_GIT_FRACTION

            val relatedIndex = match?.let { IdeRelatedFileIndex(project, it.repository.rootPath) }
                ?: session.codeSearch(metadata.request)
            val collected = IndicatorCoroutines.runCancellable(indicator) {
                collector.collectContent(metadata, localRepository?.let(::LocalGitReader), relatedIndex)
            }

            progress.phase("Rendering review context")
            indicator.fraction = RENDER_FRACTION
            val rendered = ReviewContextRenderer(options.chunkBudget, redactor).render(collected)

            progress.phase("Writing context files")
            indicator.fraction = WRITE_FRACTION
            val stored = ReviewContextStorage.write(Path.of(basePath), rendered, options.gitIgnoreContextDirectory)
            val files = stored.files.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
            indicator.fraction = 1.0

            val request = metadata.request
            val title = redactor.redact(request.title)
            outcome = Outcome(
                rendered = rendered,
                files = files,
                directory = stored.directory,
                prompt = promptFor(
                    options,
                    ReviewPrompt.Values(
                        kind = kind.requestNoun,
                        reference = reference,
                        number = request.number,
                        title = title,
                        webUrl = request.webUrl,
                        sourceBranch = request.sourceBranch,
                        targetBranch = request.targetBranch,
                        chunkCount = rendered.chunks.size,
                        contextDirectory = ReviewContextStorage.relativeDirectory(rendered.names),
                        metaFile = rendered.names.meta,
                        projectFile = rendered.names.project,
                        reportFile = "CODE_REVIEW_${rendered.names.prefix.uppercase()}${request.number}.md",
                    ),
                ),
                redactions = redactor.redactionCount,
                warnings = collected.warnings.size,
                title = ReviewPrompt.singleLine(title),
            )
        }
    }

    private fun promptFor(options: ReviewCollectorOptions, values: ReviewPrompt.Values): String? =
        when (options.promptMode) {
            ReviewPromptMode.NONE -> null
            ReviewPromptMode.BUILT_IN -> ReviewPrompt.render(options.reviewPromptTemplate, values)
            ReviewPromptMode.TEMPLATE -> {
                val template = CliqPromptTemplates.getInstance().templates().firstOrNull { it.id == options.promptTemplateId }
                ReviewPrompt.render(template?.content ?: options.reviewPromptTemplate, values)
            }
        }

    override fun onSuccess() {
        val result = outcome ?: return
        val attachment = CliqContextAttachments.Attachment(
            files = result.files,
            replacedDirectory = result.files.firstOrNull()?.parent?.parent?.path,
            prompt = result.prompt,
        )
        project.service<CliqContextAttachments>().deliver(attachment) {
            ReviewNotifications.notify(
                project,
                "Cliq tool window unavailable",
                "The review context was written to ${result.directory}.",
                NotificationType.WARNING,
            )
        }
        ReviewNotifications.notify(
            project,
            "${kind.displayName} $reference context ready",
            summary(result),
            NotificationType.INFORMATION,
        )
    }

    override fun onThrowable(error: Throwable) {
        val message = when (error) {
            is ReviewContextException, is ReviewApiException -> error.message
            is IOException -> "Could not write the context files: ${error.message}"
            else -> {
                log.warn("Unexpected failure while collecting review context", error)
                "Unexpected error: ${error.javaClass.simpleName}. See the IDE log for details."
            }
        }
        ReviewNotifications.notify(
            project,
            "Could not collect ${kind.displayName} $reference",
            message ?: "Unknown error",
            NotificationType.ERROR,
        )
    }

    private fun summary(result: Outcome): String = buildString {
        append('"').append(result.title).append("\": ")
        append(result.rendered.chunks.size).append(" chunk(s), about ")
        append((result.rendered.chunkTokens + TOKENS_PER_THOUSAND / 2) / TOKENS_PER_THOUSAND).append("k tokens. ")
        append("Files are pinned in the Cliq Context list")
        append(if (result.prompt != null) " and a review prompt is ready to send." else ".")
        if (result.redactions > 0) append(" ").append(result.redactions).append(" potential secret(s) were redacted.")
        if (result.warnings > 0) append(" ").append(result.warnings).append(" warning(s) are listed in the meta file.")
    }
}
