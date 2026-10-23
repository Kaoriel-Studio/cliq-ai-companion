package com.cliq.plugin.review.provider.github

import com.cliq.plugin.review.collect.related.RelatedFileIndex
import com.cliq.plugin.review.collect.source.ContentDecoder
import com.cliq.plugin.review.collect.source.FileContent
import com.cliq.plugin.review.collect.source.RepositoryReader
import com.cliq.plugin.review.collect.source.TreeEntry
import com.cliq.plugin.review.connect.GitRemoteParser
import com.cliq.plugin.review.connect.HostUrls
import com.cliq.plugin.review.git.GitRefNames
import com.cliq.plugin.review.http.ApiTransport
import com.cliq.plugin.review.http.ConnectionOptions
import com.cliq.plugin.review.http.JdkApiTransport
import com.cliq.plugin.review.http.RawFetch
import com.cliq.plugin.review.http.ReviewApiException
import com.cliq.plugin.review.model.ChangeStatus
import com.cliq.plugin.review.model.ChangedFile
import com.cliq.plugin.review.model.CiJob
import com.cliq.plugin.review.model.CiJobLog
import com.cliq.plugin.review.model.CiReport
import com.cliq.plugin.review.model.ReviewCommit
import com.cliq.plugin.review.model.ReviewContextException
import com.cliq.plugin.review.model.ReviewDiscussion
import com.cliq.plugin.review.model.ReviewNote
import com.cliq.plugin.review.model.ReviewProject
import com.cliq.plugin.review.model.ReviewRefs
import com.cliq.plugin.review.model.ReviewRequestDetails
import com.cliq.plugin.review.model.ReviewRequestSummary
import com.cliq.plugin.review.provider.ProviderKind
import com.cliq.plugin.review.provider.ReviewProvider
import com.cliq.plugin.review.provider.ReviewSession
import com.cliq.plugin.review.provider.closingOnFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

object GitHubProvider : ReviewProvider {

    private const val PUBLIC_HOST = "github.com"
    private const val PUBLIC_API_ROOT = "https://api.github.com/"
    private const val API_VERSION = "2022-11-28"
    private const val MAX_CONCURRENT_REQUESTS = 4

    override val kind: ProviderKind = ProviderKind.GITHUB

    fun apiRoot(webUrl: String): String =
        if (HostUrls.hostOf(webUrl) == PUBLIC_HOST) PUBLIC_API_ROOT else webUrl.trimEnd('/') + "/api/v3/"

    override fun normalizeProjectReference(raw: String): String? =
        GitRemoteParser.normalizeProjectPath(raw)?.takeIf { it.count { c -> c == '/' } == 1 }

    fun transport(webUrl: String, token: String?, options: ConnectionOptions): ApiTransport =
        JdkApiTransport(
            apiRoot = apiRoot(webUrl),
            providerName = kind.displayName,
            credentialHeaders = token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty(),
            staticHeaders = mapOf("X-GitHub-Api-Version" to API_VERSION),
            defaultAccept = "application/vnd.github+json",
            secret = token,
            options = options,
            maxConcurrentRequests = MAX_CONCURRENT_REQUESTS,
        )

    override suspend fun connect(
        webUrl: String,
        projectReference: String,
        token: String?,
        options: ConnectionOptions,
    ): ReviewSession = open(transport(webUrl, token, options), projectReference)

    suspend fun open(transport: ApiTransport, projectReference: String): ReviewSession =
        closingOnFailure(transport) {
            val api = GitHubApi(transport)
            GitHubSession(api, api.repository(projectReference), transport)
        }

    override suspend fun currentUser(webUrl: String, token: String, options: ConnectionOptions): String =
        transport(webUrl, token, options).use { GitHubApi(it).currentUser().login }
}

private class GitHubSession(
    private val api: GitHubApi,
    repository: GitHubRepository,
    private val transport: ApiTransport,
) : ReviewSession {

    private companion object {
        const val MAX_FAILED_JOB_LOGS = 3
        const val LOG_TAIL_BYTES = 64 * 1024
        const val ACTIONS_APP = "github-actions"
        val FAILED_CONCLUSIONS = setOf("failure", "timed_out", "action_required", "startup_failure", "error")
        val RUNNING_STATES = setOf("queued", "in_progress", "pending", "waiting", "requested")
        val REVIEW_STATES_WORTH_REPORTING = setOf("APPROVED", "CHANGES_REQUESTED", "DISMISSED")
    }

    private val repo = repository.fullName

    override val kind: ProviderKind = ProviderKind.GITHUB

    override val project: ReviewProject = ReviewProject(repository.fullName, repository.fullName, repository.htmlUrl)

    override suspend fun openRequests(limit: Int): List<ReviewRequestSummary> =
        api.openPullRequests(repo, limit).map { pr ->
            ReviewRequestSummary(pr.number, pr.title, pr.head.ref, pr.base.ref, pr.user?.login, pr.draft)
        }

    override suspend fun request(number: Long): ReviewRequestDetails {
        val pr = api.pullRequest(repo, number)
        val head = pr.head.sha.takeIf(GitRefNames::isSha)
        val targetTip = pr.base.sha.takeIf(GitRefNames::isSha)
        if (head == null || targetTip == null) {
            throw ReviewContextException("Pull request #${pr.number} has no commits to review yet.")
        }
        val mergeBase = api.mergeBase(repo, targetTip, head)?.takeIf(GitRefNames::isSha) ?: targetTip
        return ReviewRequestDetails(
            number = pr.number,
            title = pr.title,
            description = pr.body.orEmpty(),
            state = if (pr.merged) "merged" else pr.state,
            draft = pr.draft,
            sourceBranch = pr.head.ref,
            targetBranch = pr.base.ref,
            labels = pr.labels.map { it.name },
            webUrl = pr.htmlUrl,
            author = pr.user?.login,
            refs = ReviewRefs(baseSha = mergeBase, startSha = targetTip, headSha = head),
        )
    }

    override suspend fun changedFiles(request: ReviewRequestDetails): List<ChangedFile> =
        api.pullFiles(repo, request.number).mapNotNull { file ->
            val status = when (file.status) {
                "added", "copied" -> ChangeStatus.ADDED
                "removed" -> ChangeStatus.DELETED
                "renamed" -> ChangeStatus.RENAMED
                "unchanged" -> return@mapNotNull null
                else -> ChangeStatus.MODIFIED
            }
            ChangedFile(
                newPath = file.filename,
                oldPath = file.previousFilename?.takeIf { status == ChangeStatus.RENAMED } ?: file.filename,
                status = status,
                apiDiff = file.patch.orEmpty(),
                apiDiffComplete = file.patch != null,
                generated = false,
            )
        }

    override suspend fun discussions(number: Long): List<ReviewDiscussion> = coroutineScope {
        val issueComments = async { api.issueComments(repo, number) }
        val reviewComments = async { api.reviewComments(repo, number) }
        val reviews = async { api.reviews(repo, number) }

        val conversation = issueComments.await().mapNotNull { comment ->
            comment.body?.takeIf { it.isNotBlank() }?.let { body ->
                ReviewDiscussion(null, null, null, listOf(ReviewNote(comment.user?.login, comment.createdAt, body)))
            }
        }
        val reviewSummaries = reviews.await().mapNotNull { review ->
            val body = review.body.orEmpty().trim()
            if (body.isEmpty() && review.state !in REVIEW_STATES_WORTH_REPORTING) return@mapNotNull null
            val text = listOf("[${review.state}]", body).filter { it.isNotEmpty() }.joinToString(" ")
            ReviewDiscussion(null, null, null, listOf(ReviewNote(review.user?.login, review.submittedAt.orEmpty(), text)))
        }
        val threads = reviewComments.await()
            .groupBy { it.inReplyToId ?: it.id }
            .values
            .map { thread ->
                val root = thread.minByOrNull { it.createdAt } ?: thread.first()
                ReviewDiscussion(
                    resolved = null,
                    path = root.path,
                    line = root.line ?: root.originalLine,
                    notes = thread.sortedBy { it.createdAt }.map { ReviewNote(it.user?.login, it.createdAt, it.body.orEmpty()) },
                )
            }
        (conversation + reviewSummaries + threads).sortedBy { it.notes.first().createdAt }
    }

    override suspend fun commits(number: Long): List<ReviewCommit> =
        api.commits(repo, number).map { commit ->
            ReviewCommit(
                shortSha = commit.sha.take(8),
                author = commit.commit.author?.name.orEmpty(),
                date = commit.commit.author?.date.orEmpty(),
                message = commit.commit.message,
            )
        }

    override suspend fun ciReport(request: ReviewRequestDetails, includeFailedJobLogs: Boolean): CiReport? = coroutineScope {
        val headSha = request.refs.headSha
        val checkRuns = async { optional { api.checkRuns(repo, headSha) }.orEmpty() }
        val statuses = async { optional { api.combinedStatus(repo, headSha).statuses }.orEmpty() }

        val runs = checkRuns.await()
        val jobs = runs.map(::toCiJob) + statuses.await().map { CiJob(it.context, "status", it.state, allowFailure = false) }
        if (jobs.isEmpty()) return@coroutineScope null

        val logs = if (!includeFailedJobLogs) {
            emptyList()
        } else {
            runs.filter { it.conclusion in FAILED_CONCLUSIONS && it.app?.slug == ACTIONS_APP }
                .take(MAX_FAILED_JOB_LOGS)
                .map { run -> async { failedJobLog(run) } }
                .awaitAll()
                .filterNotNull()
        }
        CiReport(id = null, status = overallStatus(jobs), ref = request.sourceBranch, url = null, jobs = jobs, failedJobLogs = logs)
    }

    private suspend fun failedJobLog(run: GitHubCheckRun): CiJobLog? {
        val tail = optional { api.jobLogTail(repo, run.id, LOG_TAIL_BYTES) } ?: return null
        return CiJobLog(toCiJob(run), tail.toString(Charsets.UTF_8))
    }

    private fun toCiJob(run: GitHubCheckRun): CiJob =
        CiJob(run.name, run.app?.slug?.ifEmpty { null } ?: "check", run.conclusion ?: run.status, allowFailure = false)

    private fun overallStatus(jobs: List<CiJob>): String = when {
        jobs.any { it.status in FAILED_CONCLUSIONS } -> "failed"
        jobs.any { it.status in RUNNING_STATES } -> "running"
        else -> "success"
    }

    override fun repositoryReader(): RepositoryReader = GitHubRepositoryReader(api, repo)

    override fun codeSearch(request: ReviewRequestDetails): RelatedFileIndex = GitHubCodeSearchIndex(api, repo)

    override fun fetchRefspec(number: Long): String = "refs/pull/$number/head"

    override fun close() = transport.close()

    private suspend fun <T> optional(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: ReviewApiException) {
            null
        }
}

private class GitHubRepositoryReader(
    private val api: GitHubApi,
    private val repo: String,
) : RepositoryReader {

    override suspend fun content(ref: String, path: String, maxBytes: Int): FileContent =
        try {
            when (val fetched = api.rawFile(repo, path, ref, maxBytes)) {
                is RawFetch.Found -> ContentDecoder.decode(fetched.bytes, maxBytes)
                RawFetch.NotFound -> FileContent.Missing
                RawFetch.TooLarge -> FileContent.TooLarge
            }
        } catch (e: ReviewApiException) {
            FileContent.Unavailable
        }

    override suspend fun list(ref: String, directory: String): List<TreeEntry>? =
        try {
            api.directory(repo, directory, ref)?.mapNotNull { entry ->
                when (entry.type) {
                    "file" -> TreeEntry(entry.path, isDirectory = false)
                    "dir" -> TreeEntry(entry.path, isDirectory = true)
                    else -> null
                }
            }
        } catch (e: ReviewApiException) {
            null
        }
}

private class GitHubCodeSearchIndex(
    private val api: GitHubApi,
    private val repo: String,
) : RelatedFileIndex {

    private companion object {
        const val MAX_SEARCHES_PER_COLLECTION = 8
    }

    private val remainingSearches = AtomicInteger(MAX_SEARCHES_PER_COLLECTION)

    override suspend fun filesNamed(names: Collection<String>): List<String> = emptyList()

    override suspend fun filesMentioning(word: String, limit: Int): List<String> {
        if (remainingSearches.getAndDecrement() <= 0) return emptyList()
        return try {
            api.searchCode(repo, word, limit)
        } catch (e: ReviewApiException) {
            remainingSearches.set(0)
            emptyList()
        }
    }
}
