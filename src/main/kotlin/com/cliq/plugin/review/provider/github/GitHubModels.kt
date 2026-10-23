package com.cliq.plugin.review.provider.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubUser(
    val login: String = "",
)

@Serializable
data class GitHubRepository(
    val id: Long = 0,
    @SerialName("full_name") val fullName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("default_branch") val defaultBranch: String? = null,
)

@Serializable
data class GitHubBranchRef(
    val ref: String = "",
    val sha: String = "",
    val label: String = "",
)

@Serializable
data class GitHubLabel(
    val name: String = "",
)

@Serializable
data class GitHubPullRequest(
    val number: Long,
    val title: String = "",
    val body: String? = null,
    val state: String = "",
    val draft: Boolean = false,
    val merged: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val user: GitHubUser? = null,
    val head: GitHubBranchRef = GitHubBranchRef(),
    val base: GitHubBranchRef = GitHubBranchRef(),
    val labels: List<GitHubLabel> = emptyList(),
)

@Serializable
data class GitHubCommitRef(
    val sha: String = "",
)

@Serializable
data class GitHubCompare(
    @SerialName("merge_base_commit") val mergeBaseCommit: GitHubCommitRef? = null,
)

@Serializable
data class GitHubPullFile(
    val filename: String = "",
    val status: String = "",
    @SerialName("previous_filename") val previousFilename: String? = null,
    val patch: String? = null,
)

@Serializable
data class GitHubIssueComment(
    val id: Long = 0,
    val body: String? = null,
    val user: GitHubUser? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class GitHubReviewComment(
    val id: Long = 0,
    @SerialName("in_reply_to_id") val inReplyToId: Long? = null,
    val path: String? = null,
    val line: Int? = null,
    @SerialName("original_line") val originalLine: Int? = null,
    val body: String? = null,
    val user: GitHubUser? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class GitHubReview(
    val id: Long = 0,
    val state: String = "",
    val body: String? = null,
    val user: GitHubUser? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
)

@Serializable
data class GitHubCommitPerson(
    val name: String = "",
    val date: String = "",
)

@Serializable
data class GitHubCommitDetails(
    val message: String = "",
    val author: GitHubCommitPerson? = null,
)

@Serializable
data class GitHubCommit(
    val sha: String = "",
    val commit: GitHubCommitDetails = GitHubCommitDetails(),
)

@Serializable
data class GitHubApp(
    val slug: String = "",
)

@Serializable
data class GitHubCheckRun(
    val id: Long = 0,
    val name: String = "",
    val status: String = "",
    val conclusion: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val app: GitHubApp? = null,
)

@Serializable
data class GitHubCheckRuns(
    @SerialName("check_runs") val checkRuns: List<GitHubCheckRun> = emptyList(),
)

@Serializable
data class GitHubStatus(
    val context: String = "",
    val state: String = "",
)

@Serializable
data class GitHubCombinedStatus(
    val state: String = "",
    val statuses: List<GitHubStatus> = emptyList(),
)

@Serializable
data class GitHubContentEntry(
    val name: String = "",
    val path: String = "",
    val type: String = "",
)

@Serializable
data class GitHubCodeSearchItem(
    val path: String = "",
)

@Serializable
data class GitHubCodeSearch(
    val items: List<GitHubCodeSearchItem> = emptyList(),
)
