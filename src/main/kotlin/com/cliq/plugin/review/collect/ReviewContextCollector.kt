package com.cliq.plugin.review.collect

import com.cliq.plugin.review.collect.diff.LineDiffEngine
import com.cliq.plugin.review.collect.filter.SecretRedactor
import com.cliq.plugin.review.collect.related.RelatedFileIndex
import com.cliq.plugin.review.collect.related.RelatedFilesCollector
import com.cliq.plugin.review.collect.source.DirectoryListings
import com.cliq.plugin.review.collect.source.LayeredReader
import com.cliq.plugin.review.collect.source.LocalGitReader
import com.cliq.plugin.review.collect.source.RepositoryReader
import com.cliq.plugin.review.http.ReviewApiException
import com.cliq.plugin.review.model.CiReport
import com.cliq.plugin.review.model.CollectedReview
import com.cliq.plugin.review.model.CollectionDiagnostics
import com.cliq.plugin.review.model.ReviewMetadata
import com.cliq.plugin.review.provider.ReviewSession
import com.cliq.plugin.review.settings.ReviewCollectorOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

interface CollectionProgress {

    fun phase(text: String)

    fun advance(done: Int, total: Int)
}

class ReviewContextCollector(
    private val session: ReviewSession,
    private val options: ReviewCollectorOptions,
    private val redactor: SecretRedactor,
    private val lineDiff: LineDiffEngine,
    private val progress: CollectionProgress,
) {

    private companion object {
        const val LOG_TAIL_LINES = 120
    }

    val diagnostics = CollectionDiagnostics()

    suspend fun fetchMetadata(number: Long): ReviewMetadata = coroutineScope {
        progress.phase("Fetching ${session.kind.requestNoun} ${session.kind.reference(number)}")
        val request = session.request(number)

        val changes = async { session.changedFiles(request) }
        val discussions = async { optional("Discussions") { session.discussions(number) }.orEmpty() }
        val commits = async { optional("Commits") { session.commits(number) }.orEmpty() }
        val ci = async { optional("CI status") { session.ciReport(request, options.includeFailedJobLogs) }?.let(::cleanLogs) }

        ReviewMetadata(
            kind = session.kind,
            project = session.project,
            request = request,
            changes = changes.await().distinctBy { it.newPath },
            discussions = discussions.await(),
            commits = commits.await(),
            ci = ci.await(),
        )
    }

    suspend fun collectContent(
        metadata: ReviewMetadata,
        localGit: LocalGitReader?,
        relatedIndex: RelatedFileIndex,
    ): CollectedReview = coroutineScope {
        val refs = metadata.request.refs
        val remoteReader = session.repositoryReader()
        val reader: RepositoryReader = localGit?.let { LayeredReader(it, remoteReader) } ?: remoteReader
        val listings = DirectoryListings(reader, refs.headSha)
        if (localGit == null && options.diffContextLines > FileSectionBuilder.API_CONTEXT_LINES) {
            diagnostics.warn("Review commits are not available locally; wide-context diffs are computed with the IDE diff engine")
        }

        progress.phase("Collecting ${metadata.changes.size} changed file(s)")
        val sectionBuilder = FileSectionBuilder(reader, localGit, lineDiff, refs, options, redactor)
        val completed = AtomicInteger()
        val total = metadata.changes.size
        val sections = metadata.changes.map { change ->
            async {
                sectionBuilder.build(change).also { progress.advance(completed.incrementAndGet(), total) }
            }
        }

        val related = async {
            RelatedFilesCollector(
                reader = reader,
                listings = listings,
                index = relatedIndex,
                headSha = refs.headSha,
                pathFilter = options.pathFilter,
                includeTests = options.includeRelatedTests,
                maxRelatedPerChange = options.maxRelatedFilesPerChange,
                maxFileBytes = options.maxFileBytes,
                redactor = redactor,
            ).collect(metadata.changes)
        }
        val projectContext = async {
            ProjectContextCollector(reader, listings, refs.headSha, options.pathFilter, redactor).collect(metadata.changes)
        }

        CollectedReview(
            metadata = metadata,
            sections = sections.awaitAll(),
            related = related.await(),
            projectContext = projectContext.await(),
            usedLocalGit = localGit != null,
            diffContextLines = options.diffContextLines,
            warnings = diagnostics.warnings(),
        )
    }

    private fun cleanLogs(report: CiReport): CiReport =
        report.copy(
            failedJobLogs = report.failedJobLogs
                .map { log -> log.copy(tail = redactor.redact(JobLogCleaner.clean(log.tail, LOG_TAIL_LINES))) }
                .filter { it.tail.isNotBlank() },
        )

    private suspend fun <T> optional(label: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: ReviewApiException) {
            diagnostics.warn("$label unavailable: ${e.message}")
            null
        }
}
