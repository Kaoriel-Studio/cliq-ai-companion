package com.cliq.plugin.review.provider.gitlab

import com.cliq.plugin.review.http.ApiTransport
import com.cliq.plugin.review.http.BodyWindow
import com.cliq.plugin.review.http.JsonApi
import com.cliq.plugin.review.http.RawFetch
import com.cliq.plugin.review.http.ReviewHttpException
import kotlinx.serialization.builtins.ListSerializer

class GitLabApi(transport: ApiTransport) {

    private companion object {
        const val PROVIDER = "GitLab"
        const val MAX_DIFF_PAGES = 50
        const val MAX_NOTE_PAGES = 20
        const val MAX_JOB_PAGES = 5
        const val MAX_TREE_PAGES = 3
    }

    private val api = JsonApi(transport, PROVIDER) { response, _ ->
        response.header("X-Next-Page")?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    }

    private val transport: ApiTransport get() = api.transport

    suspend fun currentUser(): GitLabUser = api.get("user", GitLabUser.serializer())

    suspend fun project(pathOrId: String): GitLabProject =
        api.get("projects/${JsonApi.segment(pathOrId)}", GitLabProject.serializer())

    suspend fun openMergeRequests(projectId: Long, limit: Int): List<GitLabMergeRequest> =
        api.get(
            "projects/$projectId/merge_requests",
            ListSerializer(GitLabMergeRequest.serializer()),
            listOf(
                "state" to "opened",
                "order_by" to "updated_at",
                "sort" to "desc",
                "per_page" to limit.coerceIn(1, JsonApi.PAGE_SIZE).toString(),
            ),
        )

    suspend fun mergeRequest(projectId: Long, iid: Long): GitLabMergeRequest =
        api.get("projects/$projectId/merge_requests/$iid", GitLabMergeRequest.serializer())

    suspend fun mergeRequestDiffs(projectId: Long, iid: Long): List<GitLabFileDiff> =
        try {
            api.getPages("projects/$projectId/merge_requests/$iid/diffs", GitLabFileDiff.serializer(), MAX_DIFF_PAGES)
        } catch (e: ReviewHttpException) {
            if (e.status != 404) throw e
            api.get(
                "projects/$projectId/merge_requests/$iid/changes",
                GitLabChanges.serializer(),
                listOf("access_raw_diffs" to "true"),
            ).changes
        }

    suspend fun discussions(projectId: Long, iid: Long): List<GitLabDiscussion> =
        api.getPages("projects/$projectId/merge_requests/$iid/discussions", GitLabDiscussion.serializer(), MAX_NOTE_PAGES)

    suspend fun commits(projectId: Long, iid: Long): List<GitLabCommit> =
        api.getPages("projects/$projectId/merge_requests/$iid/commits", GitLabCommit.serializer(), MAX_NOTE_PAGES)

    suspend fun latestPipeline(projectId: Long, iid: Long): GitLabPipeline? =
        api.get(
            "projects/$projectId/merge_requests/$iid/pipelines",
            ListSerializer(GitLabPipeline.serializer()),
            listOf("per_page" to "1"),
        ).firstOrNull()

    suspend fun pipelineJobs(projectId: Long, pipelineId: Long): List<GitLabJob> =
        api.getPages("projects/$projectId/pipelines/$pipelineId/jobs", GitLabJob.serializer(), MAX_JOB_PAGES)

    suspend fun jobLogTail(projectId: Long, jobId: Long, tailBytes: Int): ByteArray? {
        val response = transport.get("projects/$projectId/jobs/$jobId/trace", maxBytes = tailBytes, window = BodyWindow.TAIL)
        return response.body.takeIf { response.isSuccess }
    }

    suspend fun rawFile(projectId: Long, path: String, ref: String, maxBytes: Int): RawFetch =
        api.raw("projects/$projectId/repository/files/${JsonApi.segment(path)}/raw", listOf("ref" to ref), maxBytes)

    suspend fun tree(projectId: Long, directory: String, ref: String): List<GitLabTreeEntry>? =
        try {
            api.getPages(
                "projects/$projectId/repository/tree",
                GitLabTreeEntry.serializer(),
                MAX_TREE_PAGES,
                buildList {
                    add("ref" to ref)
                    if (directory.isNotEmpty()) add("path" to directory)
                },
            )
        } catch (e: ReviewHttpException) {
            if (e.status == 404) null else throw e
        }

    suspend fun searchBlobs(projectId: Long, term: String, ref: String, limit: Int): List<String> =
        api.get(
            "projects/$projectId/search",
            ListSerializer(GitLabBlobSearchHit.serializer()),
            listOf(
                "scope" to "blobs",
                "search" to term,
                "ref" to ref,
                "per_page" to limit.coerceIn(1, JsonApi.PAGE_SIZE).toString(),
            ),
        ).mapNotNull { it.path ?: it.filename }.distinct()
}
