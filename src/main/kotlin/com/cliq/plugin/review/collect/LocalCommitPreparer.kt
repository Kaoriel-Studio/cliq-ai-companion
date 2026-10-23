package com.cliq.plugin.review.collect

import com.cliq.plugin.review.connect.LocalRepositoryMatch
import com.cliq.plugin.review.git.GitRefNames
import com.cliq.plugin.review.git.LocalGitRepository
import com.cliq.plugin.review.model.CollectionDiagnostics
import com.cliq.plugin.review.model.ReviewMetadata
import com.cliq.plugin.review.model.ReviewRefs
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread

object LocalCommitPreparer {

    @RequiresBackgroundThread
    fun prepare(
        match: LocalRepositoryMatch?,
        metadata: ReviewMetadata,
        reviewRefspec: String,
        fetchAllowed: Boolean,
        diagnostics: CollectionDiagnostics,
    ): LocalGitRepository? {
        val repository = match?.repository ?: return null
        val refs = metadata.request.refs
        if (hasCommits(repository, refs)) return repository

        val remoteName = match.remoteName
        if (!fetchAllowed || remoteName == null) {
            diagnostics.warn("Review commits are not in the local repository; file contents come from the hosting API")
            return null
        }

        repository.fetch(remoteName, reviewRefspec)
        val targetBranch = metadata.request.targetBranch
        if (!repository.hasCommit(refs.baseSha) && GitRefNames.isSafeBranch(targetBranch)) {
            repository.fetch(remoteName, GitRefNames.branchRefspec(targetBranch))
        }
        if (hasCommits(repository, refs)) return repository

        diagnostics.warn("Fetching the review commits from '$remoteName' did not make them available locally; file contents come from the hosting API")
        return null
    }

    private fun hasCommits(repository: LocalGitRepository, refs: ReviewRefs): Boolean =
        repository.hasCommit(refs.headSha) && repository.hasCommit(refs.baseSha)
}
