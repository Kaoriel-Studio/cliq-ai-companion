package com.cliq.plugin.review.model

import com.cliq.plugin.review.collect.diff.DiffStats
import com.cliq.plugin.review.collect.source.TreeEntry
import com.cliq.plugin.review.provider.ProviderKind
import java.util.concurrent.ConcurrentLinkedQueue

enum class DiffSource(val xmlValue: String) {
    LOCAL_GIT("local_git"),
    IDE("ide_diff"),
    API("api"),
}

data class FileSection(
    val file: ChangedFile,
    val skipReason: String? = null,
    val diff: String? = null,
    val diffSource: DiffSource? = null,
    val contentAfter: String? = null,
    val contentBefore: String? = null,
    val stats: DiffStats = DiffStats.EMPTY,
    val notes: List<String> = emptyList(),
)

enum class RelatedKind(val elementName: String, val reason: String) {
    TEST("related_test", "test"),
    DEPENDENT("related_file", "references"),
    SIBLING("related_file", "sibling"),
}

data class RelatedFile(
    val path: String,
    val ownerPath: String,
    val kind: RelatedKind,
    val content: String,
)

data class ConfigFile(
    val path: String,
    val content: String?,
    val omittedReason: String?,
)

data class DirectoryListing(
    val path: String,
    val entries: List<TreeEntry>,
    val truncated: Boolean,
)

data class ProjectContext(
    val configs: List<ConfigFile>,
    val directories: List<DirectoryListing>,
)

class CollectionDiagnostics {

    private val warnings = ConcurrentLinkedQueue<String>()

    fun warn(message: String) {
        if (warnings.size < MAX_WARNINGS && message !in warnings) warnings.add(message)
    }

    fun warnings(): List<String> = warnings.toList()

    private companion object {
        const val MAX_WARNINGS = 50
    }
}

data class ReviewMetadata(
    val kind: ProviderKind,
    val project: ReviewProject,
    val request: ReviewRequestDetails,
    val changes: List<ChangedFile>,
    val discussions: List<ReviewDiscussion>,
    val commits: List<ReviewCommit>,
    val ci: CiReport?,
)

data class CollectedReview(
    val metadata: ReviewMetadata,
    val sections: List<FileSection>,
    val related: Map<String, List<RelatedFile>>,
    val projectContext: ProjectContext,
    val usedLocalGit: Boolean,
    val diffContextLines: Int,
    val warnings: List<String>,
)
