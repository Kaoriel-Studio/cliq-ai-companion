package com.cliq.plugin.review.model

class ReviewContextException(message: String) : Exception(message)

data class ReviewProject(
    val id: String,
    val path: String,
    val webUrl: String,
)

data class ReviewRequestSummary(
    val number: Long,
    val title: String,
    val sourceBranch: String,
    val targetBranch: String,
    val author: String?,
    val draft: Boolean,
)

data class ReviewRefs(
    val baseSha: String,
    val startSha: String?,
    val headSha: String,
)

data class ReviewRequestDetails(
    val number: Long,
    val title: String,
    val description: String,
    val state: String,
    val draft: Boolean,
    val sourceBranch: String,
    val targetBranch: String,
    val labels: List<String>,
    val webUrl: String,
    val author: String?,
    val refs: ReviewRefs,
)

enum class ChangeStatus(val xmlValue: String) {
    ADDED("new"),
    MODIFIED("modified"),
    DELETED("deleted"),
    RENAMED("renamed"),
}

data class ChangedFile(
    val newPath: String,
    val oldPath: String,
    val status: ChangeStatus,
    val apiDiff: String,
    val apiDiffComplete: Boolean,
    val generated: Boolean,
)

data class ReviewNote(
    val author: String?,
    val createdAt: String,
    val body: String,
)

data class ReviewDiscussion(
    val resolved: Boolean?,
    val path: String?,
    val line: Int?,
    val notes: List<ReviewNote>,
)

data class ReviewCommit(
    val shortSha: String,
    val author: String,
    val date: String,
    val message: String,
)

data class CiJob(
    val name: String,
    val stage: String,
    val status: String,
    val allowFailure: Boolean,
)

data class CiJobLog(
    val job: CiJob,
    val tail: String,
)

data class CiReport(
    val id: String?,
    val status: String,
    val ref: String?,
    val url: String?,
    val jobs: List<CiJob>,
    val failedJobLogs: List<CiJobLog>,
)
