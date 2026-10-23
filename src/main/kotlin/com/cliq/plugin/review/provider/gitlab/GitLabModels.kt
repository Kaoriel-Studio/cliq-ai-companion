package com.cliq.plugin.review.provider.gitlab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitLabUser(
    val username: String = "",
    val name: String = "",
)

@Serializable
data class GitLabProject(
    val id: Long,
    @SerialName("path_with_namespace") val pathWithNamespace: String = "",
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("web_url") val webUrl: String = "",
)

@Serializable
data class GitLabDiffRefs(
    @SerialName("base_sha") val baseSha: String? = null,
    @SerialName("head_sha") val headSha: String? = null,
    @SerialName("start_sha") val startSha: String? = null,
)

@Serializable
data class GitLabPipeline(
    val id: Long,
    val status: String = "",
    val ref: String = "",
    val sha: String = "",
    @SerialName("web_url") val webUrl: String = "",
)

@Serializable
data class GitLabMergeRequest(
    val iid: Long,
    val title: String = "",
    val description: String? = null,
    val state: String = "",
    val draft: Boolean = false,
    @SerialName("work_in_progress") val workInProgress: Boolean = false,
    @SerialName("source_branch") val sourceBranch: String = "",
    @SerialName("target_branch") val targetBranch: String = "",
    val labels: List<String> = emptyList(),
    @SerialName("web_url") val webUrl: String = "",
    val author: GitLabUser? = null,
    @SerialName("diff_refs") val diffRefs: GitLabDiffRefs? = null,
    @SerialName("head_pipeline") val headPipeline: GitLabPipeline? = null,
    @SerialName("updated_at") val updatedAt: String = "",
) {
    val isDraft: Boolean get() = draft || workInProgress
}

@Serializable
data class GitLabFileDiff(
    @SerialName("old_path") val oldPath: String = "",
    @SerialName("new_path") val newPath: String = "",
    val diff: String = "",
    @SerialName("new_file") val newFile: Boolean = false,
    @SerialName("renamed_file") val renamedFile: Boolean = false,
    @SerialName("deleted_file") val deletedFile: Boolean = false,
    @SerialName("too_large") val tooLarge: Boolean = false,
    @SerialName("generated_file") val generatedFile: Boolean = false,
    val collapsed: Boolean = false,
)

@Serializable
data class GitLabChanges(
    val changes: List<GitLabFileDiff> = emptyList(),
    val overflow: Boolean = false,
)

@Serializable
data class GitLabNotePosition(
    @SerialName("new_path") val newPath: String? = null,
    @SerialName("old_path") val oldPath: String? = null,
    @SerialName("new_line") val newLine: Int? = null,
    @SerialName("old_line") val oldLine: Int? = null,
)

@Serializable
data class GitLabNote(
    val id: Long,
    val body: String = "",
    val author: GitLabUser? = null,
    @SerialName("created_at") val createdAt: String = "",
    val system: Boolean = false,
    val resolvable: Boolean = false,
    val resolved: Boolean = false,
    val position: GitLabNotePosition? = null,
)

@Serializable
data class GitLabDiscussion(
    val id: String = "",
    @SerialName("individual_note") val individualNote: Boolean = false,
    val notes: List<GitLabNote> = emptyList(),
)

@Serializable
data class GitLabJob(
    val id: Long,
    val name: String = "",
    val stage: String = "",
    val status: String = "",
    @SerialName("allow_failure") val allowFailure: Boolean = false,
    @SerialName("web_url") val webUrl: String = "",
)

@Serializable
data class GitLabCommit(
    val id: String = "",
    @SerialName("short_id") val shortId: String = "",
    val title: String = "",
    val message: String = "",
    @SerialName("author_name") val authorName: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class GitLabTreeEntry(
    val name: String = "",
    val path: String = "",
    val type: String = "",
)

@Serializable
data class GitLabBlobSearchHit(
    val path: String? = null,
    val filename: String? = null,
)
