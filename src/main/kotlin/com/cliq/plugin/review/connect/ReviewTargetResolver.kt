package com.cliq.plugin.review.connect

import com.cliq.plugin.review.git.LocalGitAccess
import com.cliq.plugin.review.git.LocalGitRepository
import com.cliq.plugin.review.settings.ReviewHostStore
import com.cliq.plugin.review.settings.ReviewHost
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.project.Project

data class ReviewTarget(
    val remote: GitRemoteLocation?,
    val host: ReviewHost?,
    val projectPath: String?,
    val currentBranch: String?,
)

class LocalRepositoryMatch(
    val repository: LocalGitRepository,
    val remoteName: String?,
)

object ReviewTargetResolver {

    private val PREFERRED_REMOTES = listOf("origin", "upstream")

    fun localRepositories(project: Project): List<LocalGitRepository> =
        project.serviceOrNull<LocalGitAccess>()?.repositories().orEmpty()

    fun resolve(project: Project, store: ReviewHostStore): ReviewTarget {
        val repository = localRepositories(project).firstOrNull()
        val remote = repository?.let(::preferredRemote)
        val host = remote?.let(store::findHost)
        val projectPath = remote?.let { location -> host?.let { HostUrls.projectPath(location, it.url) } ?: location.projectPath }
        return ReviewTarget(remote, host, projectPath, repository?.currentBranch)
    }

    fun match(repositories: List<LocalGitRepository>, projectPath: String): LocalRepositoryMatch? {
        val wanted = projectPath.lowercase()
        repositories.forEach { repository ->
            val remote = repository.remotes.firstOrNull { remote ->
                remote.urls.any { url ->
                    val path = GitRemoteParser.parse(url)?.projectPath?.lowercase() ?: return@any false
                    path == wanted || path.endsWith("/$wanted")
                }
            }
            if (remote != null) return LocalRepositoryMatch(repository, remote.name)
        }
        return repositories.firstOrNull()?.let { LocalRepositoryMatch(it, null) }
    }

    private fun preferredRemote(repository: LocalGitRepository): GitRemoteLocation? =
        repository.remotes
            .sortedBy { remote -> PREFERRED_REMOTES.indexOf(remote.name).takeIf { it >= 0 } ?: PREFERRED_REMOTES.size }
            .flatMap { it.urls }
            .firstNotNullOfOrNull(GitRemoteParser::parse)
}
