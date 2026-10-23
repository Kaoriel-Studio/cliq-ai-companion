package com.cliq.plugin.review.provider.gitlab

import com.cliq.plugin.review.collect.related.RelatedFileIndex
import com.cliq.plugin.review.collect.source.ContentDecoder
import com.cliq.plugin.review.collect.source.FileContent
import com.cliq.plugin.review.collect.source.RepositoryReader
import com.cliq.plugin.review.collect.source.TreeEntry
import com.cliq.plugin.review.connect.GitRemoteParser
import com.cliq.plugin.review.git.GitRefNames
import com.cliq.plugin.review.http.ApiTransport
import com.cliq.plugin.review.http.ConnectionOptions
import com.cliq.plugin.review.http.JdkApiTransport
import com.cliq.plugin.review.http.RawFetch
import com.cliq.plugin.review.http.ReviewApiException
import com.cliq.plugin.review.http.ReviewHttpException
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object GitLabProvider : ReviewProvider {

    private const val MAX_CONCURRENT_REQUESTS = 6

    override val kind: ProviderKind = ProviderKind.GITLAB

    fun apiRoot(webUrl: String): String = webUrl.trimEnd('/') + "/api/v4/"

    override fun normalizeProjectReference(raw: String): String? = GitRemoteParser.normalizeProjectReference(raw)

    fun transport(webUrl: String, token: String?, options: ConnectionOptions): ApiTransport =
        JdkApiTransport(
            apiRoot = apiRoot(webUrl),
            providerName = kind.displayName,
            credentialHeaders = token?.let { mapOf("PRIVATE-TOKEN" to it) }.orEmpty(),
            staticHeaders = emptyMap(),
            defaultAccept = "application/json",
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
            val api = GitLabApi(transport)
            GitLabSession(api, api.project(projectReference), transport)
        }

    override suspend fun currentUser(webUrl: String, token: String, options: ConnectionOptions): String =
        transport(webUrl, token, options).use { GitLabApi(it).currentUser().username }
}

private class GitLabSession(
    private val api: GitLabApi,
    private val gitLabProject: GitLabProject,
    private val transport: ApiTransport,
) : ReviewSession {

    private companion object {
        const val MAX_FAILED_JOB_LOGS = 3
        const val LOG_TAIL_BYTES = 64 * 1024
    }

    private val projectId = gitLabProject.id
    private val mergeRequests = ConcurrentHashMap<Long, GitLabMergeRequest>()

    override val kind: ProviderKind = ProviderKind.GITLAB

    override val project: ReviewProject =
        ReviewProject(projectId.toString(), gitLabProject.pathWithNamespace, gitLabProject.webUrl)

    override suspend fun openRequests(limit: Int): List<ReviewRequestSummary> =
        api.openMergeRequests(projectId, limit).map { mr ->
            ReviewRequestSummary(mr.iid, mr.title, mr.sourceBranch, mr.targetBranch, mr.author?.username, mr.isDraft)
        }

    override suspend fun request(number: Long): ReviewRequestDetails {
        val mr = api.mergeRequest(projectId, number).also { mergeRequests[number] = it }
        val diffRefs = mr.diffRefs
        val head = diffRefs?.headSha?.takeIf(GitRefNames::isSha)
        val base = (diffRefs?.baseSha ?: diffRefs?.startSha)?.takeIf(GitRefNames::isSha)
        if (head == null || base == null) {
            throw ReviewContextException(
                "Merge request !${mr.iid} has no diff yet. Push commits or wait for GitLab to finish processing it.",
            )
        }
        return ReviewRequestDetails(
            number = mr.iid,
            title = mr.title,
            description = mr.description.orEmpty(),
            state = mr.state,
            draft = mr.isDraft,
            sourceBranch = mr.sourceBranch,
            targetBranch = mr.targetBranch,
            labels = mr.labels,
            webUrl = mr.webUrl,
            author = mr.author?.username,
            refs = ReviewRefs(base, diffRefs?.startSha?.takeIf(GitRefNames::isSha), head),
        )
    }

    override suspend fun changedFiles(request: ReviewRequestDetails): List<ChangedFile> =
        api.mergeRequestDiffs(projectId, request.number).map { diff ->
            ChangedFile(
                newPath = diff.newPath,
                oldPath = diff.oldPath.ifEmpty { diff.newPath },
                status = when {
                    diff.newFile -> ChangeStatus.ADDED
                    diff.deletedFile -> ChangeStatus.DELETED
                    diff.renamedFile -> ChangeStatus.RENAMED
                    else -> ChangeStatus.MODIFIED
                },
                apiDiff = diff.diff,
                apiDiffComplete = !diff.tooLarge && !diff.collapsed,
                generated = diff.generatedFile,
            )
        }

    override suspend fun discussions(number: Long): List<ReviewDiscussion> =
        api.discussions(projectId, number).mapNotNull { discussion ->
            val notes = discussion.notes.filterNot(GitLabNote::system)
            if (notes.isEmpty()) return@mapNotNull null
            val position = notes.firstNotNullOfOrNull { it.position }
            val resolvable = notes.filter { it.resolvable }
            ReviewDiscussion(
                resolved = resolvable.takeIf { it.isNotEmpty() }?.all { it.resolved },
                path = position?.newPath ?: position?.oldPath,
                line = position?.newLine ?: position?.oldLine,
                notes = notes.map { ReviewNote(it.author?.username, it.createdAt, it.body) },
            )
        }

    override suspend fun commits(number: Long): List<ReviewCommit> =
        api.commits(projectId, number).map { commit ->
            ReviewCommit(
                shortSha = commit.shortId.ifEmpty { commit.id.take(8) },
                author = commit.authorName,
                date = commit.createdAt,
                message = commit.message.ifBlank { commit.title },
            )
        }

    override suspend fun ciReport(request: ReviewRequestDetails, includeFailedJobLogs: Boolean): CiReport? = coroutineScope {
        val mergeRequest = mergeRequests[request.number] ?: api.mergeRequest(projectId, request.number)
        val pipeline = mergeRequest.headPipeline ?: api.latestPipeline(projectId, request.number) ?: return@coroutineScope null
        val jobs = api.pipelineJobs(projectId, pipeline.id)
        val logs = if (!includeFailedJobLogs) {
            emptyList()
        } else {
            jobs.filter { it.status == "failed" }
                .sortedBy { it.allowFailure }
                .take(MAX_FAILED_JOB_LOGS)
                .map { job -> async { failedJobLog(job) } }
                .awaitAll()
                .filterNotNull()
        }
        CiReport(pipeline.id.toString(), pipeline.status, pipeline.ref, pipeline.webUrl, jobs.map(::toCiJob), logs)
    }

    private suspend fun failedJobLog(job: GitLabJob): CiJobLog? {
        val tail = try {
            api.jobLogTail(projectId, job.id, LOG_TAIL_BYTES)
        } catch (e: ReviewApiException) {
            null
        } ?: return null
        return CiJobLog(toCiJob(job), tail.toString(Charsets.UTF_8))
    }

    private fun toCiJob(job: GitLabJob): CiJob = CiJob(job.name, job.stage, job.status, job.allowFailure)

    override fun repositoryReader(): RepositoryReader = GitLabRepositoryReader(api, projectId)

    override fun codeSearch(request: ReviewRequestDetails): RelatedFileIndex =
        GitLabCodeSearch(api, projectId, request.targetBranch)

    override fun fetchRefspec(number: Long): String = "refs/merge-requests/$number/head"

    override fun close() = transport.close()
}

private class GitLabRepositoryReader(
    private val api: GitLabApi,
    private val projectId: Long,
) : RepositoryReader {

    override suspend fun content(ref: String, path: String, maxBytes: Int): FileContent =
        try {
            when (val fetched = api.rawFile(projectId, path, ref, maxBytes)) {
                is RawFetch.Found -> ContentDecoder.decode(fetched.bytes, maxBytes)
                RawFetch.NotFound -> FileContent.Missing
                RawFetch.TooLarge -> FileContent.TooLarge
            }
        } catch (e: ReviewApiException) {
            FileContent.Unavailable
        }

    override suspend fun list(ref: String, directory: String): List<TreeEntry>? =
        try {
            api.tree(projectId, directory, ref)?.mapNotNull { entry ->
                when (entry.type) {
                    "blob" -> TreeEntry(entry.path, isDirectory = false)
                    "tree" -> TreeEntry(entry.path, isDirectory = true)
                    else -> null
                }
            }
        } catch (e: ReviewApiException) {
            null
        }
}

private class GitLabCodeSearch(
    private val api: GitLabApi,
    private val projectId: Long,
    private val ref: String,
) : RelatedFileIndex {

    private val unsupported = AtomicBoolean(false)

    override suspend fun filesNamed(names: Collection<String>): List<String> = emptyList()

    override suspend fun filesMentioning(word: String, limit: Int): List<String> {
        if (unsupported.get()) return emptyList()
        return try {
            api.searchBlobs(projectId, word, ref, limit)
        } catch (e: ReviewHttpException) {
            if (e.status in 400..404) unsupported.set(true)
            emptyList()
        } catch (e: ReviewApiException) {
            emptyList()
        }
    }
}
