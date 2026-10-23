package com.cliq.plugin.review.provider.github

import com.cliq.plugin.review.http.ApiTransport
import com.cliq.plugin.review.http.BodyWindow
import com.cliq.plugin.review.http.JsonApi
import com.cliq.plugin.review.http.RawFetch
import com.cliq.plugin.review.http.ReviewHttpException
import kotlinx.serialization.builtins.ListSerializer

class GitHubApi(transport: ApiTransport) {

    companion object {
        const val RAW_MEDIA_TYPE = "application/vnd.github.raw+json"
        private const val PROVIDER = "GitHub"
        private const val MAX_FILE_PAGES = 30
        private const val MAX_COMMENT_PAGES = 20
        private const val MAX_REVIEW_PAGES = 10
        private const val MAX_COMMIT_PAGES = 3
        private const val LOG_PROBE_BYTES = 4 * 1024
        private val NEXT_LINK = Regex("""<[^>]*>\s*;\s*rel="next"""")

        fun repositoryPath(fullName: String): String = "repos/${JsonApi.pathSegments(fullName)}"
    }

    private val api = JsonApi(transport, PROVIDER) { response, page ->
        response.header("Link")?.takeIf { NEXT_LINK.containsMatchIn(it) }?.let { page + 1 }
    }

    private val transport: ApiTransport get() = api.transport

    suspend fun currentUser(): GitHubUser = api.get("user", GitHubUser.serializer())

    suspend fun repository(fullName: String): GitHubRepository =
        api.get(repositoryPath(fullName), GitHubRepository.serializer())

    suspend fun openPullRequests(repo: String, limit: Int): List<GitHubPullRequest> =
        api.get(
            "${repositoryPath(repo)}/pulls",
            ListSerializer(GitHubPullRequest.serializer()),
            listOf(
                "state" to "open",
                "sort" to "updated",
                "direction" to "desc",
                "per_page" to limit.coerceIn(1, JsonApi.PAGE_SIZE).toString(),
            ),
        )

    suspend fun pullRequest(repo: String, number: Long): GitHubPullRequest =
        api.get("${repositoryPath(repo)}/pulls/$number", GitHubPullRequest.serializer())

    suspend fun mergeBase(repo: String, baseSha: String, headSha: String): String? =
        api.get(
            "${repositoryPath(repo)}/compare/$baseSha...$headSha",
            GitHubCompare.serializer(),
            listOf("per_page" to "1"),
        ).mergeBaseCommit?.sha

    suspend fun pullFiles(repo: String, number: Long): List<GitHubPullFile> =
        api.getPages("${repositoryPath(repo)}/pulls/$number/files", GitHubPullFile.serializer(), MAX_FILE_PAGES)

    suspend fun issueComments(repo: String, number: Long): List<GitHubIssueComment> =
        api.getPages("${repositoryPath(repo)}/issues/$number/comments", GitHubIssueComment.serializer(), MAX_COMMENT_PAGES)

    suspend fun reviewComments(repo: String, number: Long): List<GitHubReviewComment> =
        api.getPages("${repositoryPath(repo)}/pulls/$number/comments", GitHubReviewComment.serializer(), MAX_COMMENT_PAGES)

    suspend fun reviews(repo: String, number: Long): List<GitHubReview> =
        api.getPages("${repositoryPath(repo)}/pulls/$number/reviews", GitHubReview.serializer(), MAX_REVIEW_PAGES)

    suspend fun commits(repo: String, number: Long): List<GitHubCommit> =
        api.getPages("${repositoryPath(repo)}/pulls/$number/commits", GitHubCommit.serializer(), MAX_COMMIT_PAGES)

    suspend fun checkRuns(repo: String, sha: String): List<GitHubCheckRun> =
        api.get(
            "${repositoryPath(repo)}/commits/$sha/check-runs",
            GitHubCheckRuns.serializer(),
            listOf("per_page" to JsonApi.PAGE_SIZE.toString()),
        ).checkRuns

    suspend fun combinedStatus(repo: String, sha: String): GitHubCombinedStatus =
        api.get("${repositoryPath(repo)}/commits/$sha/status", GitHubCombinedStatus.serializer())

    suspend fun jobLogTail(repo: String, jobId: Long, tailBytes: Int): ByteArray? {
        val path = "${repositoryPath(repo)}/actions/jobs/$jobId/logs"
        val probe = transport.get(path, maxBytes = LOG_PROBE_BYTES)
        val response = when {
            probe.isRedirect -> probe.header("Location")?.let { transport.getExternal(it, tailBytes, BodyWindow.TAIL) }
            probe.isSuccess && probe.truncated -> transport.get(path, maxBytes = tailBytes, window = BodyWindow.TAIL)
            else -> probe
        } ?: return null
        return response.body.takeIf { response.isSuccess }
    }

    suspend fun rawFile(repo: String, path: String, ref: String, maxBytes: Int): RawFetch =
        api.raw("${repositoryPath(repo)}/contents/${JsonApi.pathSegments(path)}", listOf("ref" to ref), maxBytes, RAW_MEDIA_TYPE)

    suspend fun directory(repo: String, directory: String, ref: String): List<GitHubContentEntry>? {
        val suffix = if (directory.isEmpty()) "" else "/${JsonApi.pathSegments(directory)}"
        return try {
            api.get("${repositoryPath(repo)}/contents$suffix", ListSerializer(GitHubContentEntry.serializer()), listOf("ref" to ref))
        } catch (e: ReviewHttpException) {
            if (e.status == 404) null else throw e
        }
    }

    suspend fun searchCode(repo: String, word: String, limit: Int): List<String> =
        api.get(
            "search/code",
            GitHubCodeSearch.serializer(),
            listOf("q" to "$word repo:$repo", "per_page" to limit.coerceIn(1, JsonApi.PAGE_SIZE).toString()),
        ).items.map { it.path }.distinct()
}
