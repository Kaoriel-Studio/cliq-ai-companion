package com.cliq.plugin.review.collect

import com.cliq.plugin.review.collect.budget.TokenEstimator
import com.cliq.plugin.review.collect.diff.DiffStats
import com.cliq.plugin.review.collect.diff.LineDiffEngine
import com.cliq.plugin.review.collect.diff.UnifiedDiffFormatter
import com.cliq.plugin.review.collect.filter.SecretRedactor
import com.cliq.plugin.review.model.ChangeStatus
import com.cliq.plugin.review.model.ChangedFile
import com.cliq.plugin.review.model.DiffSource
import com.cliq.plugin.review.model.FileSection
import com.cliq.plugin.review.model.ReviewRefs
import com.cliq.plugin.review.collect.source.FileContent
import com.cliq.plugin.review.collect.source.LocalGitReader
import com.cliq.plugin.review.collect.source.RepositoryReader
import com.cliq.plugin.review.settings.ReviewCollectorOptions
import com.cliq.plugin.review.settings.PreChangeContentMode

class FileSectionBuilder(
    private val reader: RepositoryReader,
    private val localGit: LocalGitReader?,
    private val lineDiff: LineDiffEngine,
    private val refs: ReviewRefs,
    private val options: ReviewCollectorOptions,
    private val redactor: SecretRedactor,
) {

    companion object {
        const val API_CONTEXT_LINES = 3
        private const val REWRITE_RATIO_PERCENT = 50
        private const val REWRITE_MIN_CHANGED_LINES = 20
    }

    private class ResolvedDiff(val text: String, val source: DiffSource)

    private inner class LazyContent(private val ref: String, private val path: String) {
        private var loaded: FileContent? = null

        suspend fun get(): FileContent = loaded ?: reader.content(ref, path, options.maxFileBytes).also { loaded = it }

        suspend fun text(): String? = (get() as? FileContent.Text)?.value?.let(UnifiedDiffFormatter::normalizeLineEndings)

        val isKnownBinary: Boolean get() = loaded == FileContent.Binary
    }

    suspend fun build(file: ChangedFile): FileSection {
        skipReason(file)?.let { return FileSection(file, skipReason = it) }

        val notes = mutableListOf<String>()
        val after = if (file.status == ChangeStatus.DELETED) null else reader.content(refs.headSha, file.newPath, options.maxFileBytes)
        if (after == FileContent.Binary) return FileSection(file, skipReason = "binary")
        after?.let { describeUnavailable("content_after", it)?.let(notes::add) }
        val afterText = (after as? FileContent.Text)?.value?.let(UnifiedDiffFormatter::normalizeLineEndings)

        val before = LazyContent(refs.baseSha, file.oldPath)
        val diff = resolveDiff(file, afterText, before)
        val stats = diff?.let { DiffStats.of(it.text) } ?: DiffStats.EMPTY

        val pureRename = file.status == ChangeStatus.RENAMED && diff != null && stats.changedLines == 0
        if (file.status == ChangeStatus.ADDED && afterText != null) notes += "diff omitted: new file, the full content is in content_after"
        if (pureRename) notes += "renamed without content changes"
        if (diff == null && file.status != ChangeStatus.ADDED) notes += "diff unavailable from git and the hosting API"

        val beforeText = if (includeBefore(file, diff, stats, afterText)) before.text() else null
        if (before.isKnownBinary) return FileSection(file, skipReason = "binary")

        return FileSection(
            file = file,
            diff = diff?.text?.let(redactor::redact),
            diffSource = diff?.source,
            contentAfter = afterText.takeUnless { pureRename }?.let(redactor::redact),
            contentBefore = beforeText?.let(redactor::redact),
            stats = stats,
            notes = notes,
        )
    }

    private fun skipReason(file: ChangedFile): String? {
        options.pathFilter.verdict(file.newPath)?.let { return it.reason }
        if (file.oldPath != file.newPath) options.pathFilter.verdict(file.oldPath)?.let { return it.reason }
        return if (file.generated) "generated" else null
    }

    private suspend fun resolveDiff(file: ChangedFile, afterText: String?, before: LazyContent): ResolvedDiff? {
        if (file.status == ChangeStatus.ADDED && afterText != null) return null

        localGit?.diff(refs.baseSha, refs.headSha, file.oldPath, file.newPath, options.diffContextLines)
            ?.takeIf { it.isNotBlank() }
            ?.let { return ResolvedDiff(it, DiffSource.LOCAL_GIT) }

        val apiDiff = file.apiDiff.takeIf { it.isNotBlank() }?.let { withHeader(file, it) }
        if (apiDiff != null && file.apiDiffComplete && options.diffContextLines <= API_CONTEXT_LINES) {
            return ResolvedDiff(apiDiff, DiffSource.API)
        }

        if (afterText != null && (file.status == ChangeStatus.MODIFIED || file.status == ChangeStatus.RENAMED)) {
            before.text()?.let { beforeText ->
                ideDiff(file, beforeText, afterText)?.let { return ResolvedDiff(it, DiffSource.IDE) }
            }
        }
        return apiDiff?.let { ResolvedDiff(it, DiffSource.API) }
    }

    private fun ideDiff(file: ChangedFile, beforeText: String, afterText: String): String? {
        val changes = lineDiff.compare(beforeText, afterText) ?: return null
        return try {
            UnifiedDiffFormatter.format(file.oldPath, file.newPath, beforeText, afterText, changes, options.diffContextLines)
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    private fun includeBefore(file: ChangedFile, diff: ResolvedDiff?, stats: DiffStats, afterText: String?): Boolean =
        when (file.status) {
            ChangeStatus.ADDED -> false
            ChangeStatus.DELETED -> diff == null
            ChangeStatus.MODIFIED, ChangeStatus.RENAMED -> when (options.preChangeContent) {
                PreChangeContentMode.NEVER -> false
                PreChangeContentMode.ALWAYS -> true
                PreChangeContentMode.REWRITES_ONLY -> isRewrite(stats, afterText)
            }
        }

    private fun isRewrite(stats: DiffStats, afterText: String?): Boolean {
        if (stats.changedLines < REWRITE_MIN_CHANGED_LINES) return false
        val approximateUnion = (afterText?.let(TokenEstimator::lineCount) ?: 0) + stats.deletions
        return stats.changedLines * 100 >= approximateUnion * REWRITE_RATIO_PERCENT
    }

    private fun withHeader(file: ChangedFile, body: String): String =
        "--- a/${file.oldPath}\n+++ b/${file.newPath}\n$body".trimEnd('\n')

    private fun describeUnavailable(section: String, content: FileContent): String? = when (content) {
        FileContent.TooLarge -> "$section omitted: larger than ${options.maxFileBytes / 1024} KB"
        FileContent.Missing -> "$section unavailable at the head commit"
        FileContent.Unavailable -> "$section could not be fetched"
        is FileContent.Text, FileContent.Binary -> null
    }
}
