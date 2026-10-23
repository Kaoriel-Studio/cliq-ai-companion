package com.cliq.plugin.review.provider

import com.cliq.plugin.review.collect.related.RelatedFileIndex
import com.cliq.plugin.review.collect.source.RepositoryReader
import com.cliq.plugin.review.http.ApiTransport
import com.cliq.plugin.review.http.ConnectionOptions
import com.cliq.plugin.review.model.ChangedFile
import com.cliq.plugin.review.model.CiReport
import com.cliq.plugin.review.model.ReviewCommit
import com.cliq.plugin.review.model.ReviewDiscussion
import com.cliq.plugin.review.model.ReviewProject
import com.cliq.plugin.review.model.ReviewRequestDetails
import com.cliq.plugin.review.model.ReviewRequestSummary
import com.cliq.plugin.review.provider.github.GitHubProvider
import com.cliq.plugin.review.provider.gitlab.GitLabProvider

enum class ProviderKind(
    val displayName: String,
    val requestNoun: String,
    val referencePrefix: String,
    val filePrefix: String,
    val numberLabel: String,
    val tokenHint: String,
) {
    GITLAB(
        displayName = "GitLab",
        requestNoun = "merge request",
        referencePrefix = "!",
        filePrefix = "mr",
        numberLabel = "Merge request IID:",
        tokenHint = "Personal access token with the read_api scope.",
    ),
    GITHUB(
        displayName = "GitHub",
        requestNoun = "pull request",
        referencePrefix = "#",
        filePrefix = "pr",
        numberLabel = "Pull request number:",
        tokenHint = "Fine-grained token with read access to contents, pull requests, checks and actions, " +
            "or a classic token with the repo scope. Optional for public repositories.",
    );

    fun reference(number: Long): String = "$referencePrefix$number"

    companion object {
        fun parse(raw: String?): ProviderKind = entries.firstOrNull { it.name == raw } ?: GITLAB

        fun guess(host: String): ProviderKind = if ("github" in host.lowercase()) GITHUB else GITLAB
    }
}

interface ReviewSession : AutoCloseable {

    val kind: ProviderKind

    val project: ReviewProject

    suspend fun openRequests(limit: Int): List<ReviewRequestSummary>

    suspend fun request(number: Long): ReviewRequestDetails

    suspend fun changedFiles(request: ReviewRequestDetails): List<ChangedFile>

    suspend fun discussions(number: Long): List<ReviewDiscussion>

    suspend fun commits(number: Long): List<ReviewCommit>

    suspend fun ciReport(request: ReviewRequestDetails, includeFailedJobLogs: Boolean): CiReport?

    fun repositoryReader(): RepositoryReader

    fun codeSearch(request: ReviewRequestDetails): RelatedFileIndex

    fun fetchRefspec(number: Long): String
}

interface ReviewProvider {

    val kind: ProviderKind

    fun normalizeProjectReference(raw: String): String?

    suspend fun connect(webUrl: String, projectReference: String, token: String?, options: ConnectionOptions): ReviewSession

    suspend fun currentUser(webUrl: String, token: String, options: ConnectionOptions): String
}

object ReviewProviders {

    fun of(kind: ProviderKind): ReviewProvider = when (kind) {
        ProviderKind.GITLAB -> GitLabProvider
        ProviderKind.GITHUB -> GitHubProvider
    }
}

internal inline fun <T> closingOnFailure(transport: ApiTransport, block: () -> T): T {
    var opened = false
    try {
        return block().also { opened = true }
    } finally {
        if (!opened) transport.close()
    }
}
