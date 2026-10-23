package com.cliq.plugin.review.collect

import com.cliq.plugin.review.collect.budget.ChunkBudget
import com.cliq.plugin.review.collect.diff.LineChange
import com.cliq.plugin.review.collect.diff.LineDiffEngine
import com.cliq.plugin.review.collect.filter.PathFilter
import com.cliq.plugin.review.collect.filter.PathPatternSet
import com.cliq.plugin.review.collect.filter.SecretRedactor
import com.cliq.plugin.review.http.FakeApiTransport
import com.cliq.plugin.review.http.JsonApi
import com.cliq.plugin.review.model.CollectedReview
import com.cliq.plugin.review.provider.ReviewSession
import com.cliq.plugin.review.provider.github.GitHubApi
import com.cliq.plugin.review.settings.CliqReviewSettings
import com.cliq.plugin.review.settings.PreChangeContentMode
import com.cliq.plugin.review.settings.ReviewCollectorOptions
import com.cliq.plugin.review.settings.ReviewPromptMode
import kotlinx.coroutines.runBlocking

object NoProgress : CollectionProgress {
    override fun phase(text: String) = Unit
    override fun advance(done: Int, total: Int) = Unit
}

object PrefixSuffixDiffEngine : LineDiffEngine {
    override fun compare(before: String, after: String): List<LineChange> {
        val a = before.split('\n')
        val b = after.split('\n')
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        if (prefix == a.size && prefix == b.size) return emptyList()
        return listOf(LineChange(prefix, a.size - suffix, prefix, b.size - suffix))
    }
}

fun testOptions(
    budget: ChunkBudget = ChunkBudget(CliqReviewSettings.DEFAULT_CHUNK_TOKEN_BUDGET, CliqReviewSettings.DEFAULT_CHUNK_LINE_BUDGET),
    contextLines: Int = 3,
) = ReviewCollectorOptions(
    chunkBudget = budget,
    diffContextLines = contextLines,
    maxFileBytes = 256 * 1024,
    pathFilter = PathFilter(PathPatternSet.parseLines(CliqReviewSettings.DEFAULT_EXCLUDED_PATTERNS)),
    includeRelatedTests = true,
    maxRelatedFilesPerChange = 3,
    preChangeContent = PreChangeContentMode.REWRITES_ONLY,
    includeFailedJobLogs = true,
    fetchMissingCommits = false,
    gitIgnoreContextDirectory = true,
    reviewPromptTemplate = CliqReviewSettings.DEFAULT_REVIEW_PROMPT_TEMPLATE,
    promptMode = ReviewPromptMode.BUILT_IN,
    promptTemplateId = "",
    secretKeywords = emptyList(),
)

fun collectWith(
    session: ReviewSession,
    number: Long,
    options: ReviewCollectorOptions,
    redactor: SecretRedactor,
): CollectedReview = runBlocking {
    val collector = ReviewContextCollector(session, options, redactor, PrefixSuffixDiffEngine, NoProgress)
    val metadata = collector.fetchMetadata(number)
    collector.collectContent(metadata, null, session.codeSearch(metadata.request))
}

fun FakeApiTransport.gitLabFiles(projectId: Long, ref: String, contents: Map<String, String>) = on { request ->
    if (request.query["ref"] != ref) return@on null
    contents.entries.firstOrNull { (path, _) ->
        request.path == "projects/$projectId/repository/files/${JsonApi.segment(path)}/raw"
    }?.let { FakeApiTransport.Reply(200, it.value.toByteArray()) }
}

fun FakeApiTransport.gitHubFiles(repo: String, ref: String, contents: Map<String, String>) = on { request ->
    if (request.query["ref"] != ref || request.accept != GitHubApi.RAW_MEDIA_TYPE) return@on null
    contents.entries.firstOrNull { (path, _) ->
        request.path == "repos/$repo/contents/${JsonApi.pathSegments(path)}"
    }?.let { FakeApiTransport.Reply(200, it.value.toByteArray()) }
}
