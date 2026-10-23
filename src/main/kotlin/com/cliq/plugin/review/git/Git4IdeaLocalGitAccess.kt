package com.cliq.plugin.review.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import git4idea.commands.GitLineHandler
import git4idea.fetch.GitFetchSupport
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import git4idea.util.GitFileUtils

class Git4IdeaLocalGitAccess(private val project: Project) : LocalGitAccess {

    override fun repositories(): List<LocalGitRepository> {
        val basePath = project.basePath
        return GitRepositoryManager.getInstance(project).repositories
            .sortedBy { if (it.root.path == basePath) 0 else 1 }
            .map { Git4IdeaRepository(project, it) }
    }
}

private class Git4IdeaRepository(
    private val project: Project,
    private val repository: GitRepository,
) : LocalGitRepository {

    override val rootPath: String
        get() = repository.root.path

    override val remotes: List<LocalGitRemote>
        get() = repository.remotes.map { LocalGitRemote(it.name, it.urls) }

    override val currentBranch: String?
        get() = repository.currentBranchName

    override fun hasCommit(sha: String): Boolean {
        if (!GitRefNames.isSha(sha)) return false
        return run(GitCommand.CAT_FILE) { addParameters("-e", "$sha^{commit}") }.success()
    }

    override fun fetch(remoteName: String, refspec: String): Boolean {
        val remote = repository.remotes.firstOrNull { it.name == remoteName } ?: return false
        return try {
            GitFetchSupport.fetchSupport(project).fetch(repository, remote, refspec).throwExceptionIfFailed()
            true
        } catch (e: VcsException) {
            false
        }
    }

    override fun diff(baseSha: String, headSha: String, oldPath: String, newPath: String, contextLines: Int): String? {
        if (!GitRefNames.isSha(baseSha) || !GitRefNames.isSha(headSha)) return null
        if (!GitRefNames.isSafeRepoPath(oldPath) || !GitRefNames.isSafeRepoPath(newPath)) return null
        val result = run(GitCommand.DIFF) {
            addParameters("--no-color", "--no-ext-diff", "--no-textconv", "-M", "-U$contextLines", baseSha, headSha)
            endOptions()
            addParameters(listOf(oldPath, newPath).distinct())
        }
        return if (result.success()) result.outputAsJoinedString else null
    }

    override fun objectSize(ref: String, path: String): Long? {
        if (!isSafeRefAndPath(ref, path)) return null
        val result = run(GitCommand.CAT_FILE) { addParameters("-s", "$ref:$path") }
        return if (result.success()) result.outputAsJoinedString.trim().toLongOrNull() else null
    }

    override fun fileContent(ref: String, path: String): ByteArray? {
        if (!isSafeRefAndPath(ref, path)) return null
        return try {
            GitFileUtils.getFileContent(project, repository.root, ref, path)
        } catch (e: VcsException) {
            null
        }
    }

    override fun listDirectory(ref: String, directory: String): List<LocalTreeEntry>? {
        if (!GitRefNames.isSha(ref)) return null
        if (directory.isNotEmpty() && !GitRefNames.isSafeRepoPath(directory)) return null
        val result = run(GitCommand.LS_TREE) {
            addParameters("-z", "--full-tree", ref)
            if (directory.isNotEmpty()) {
                endOptions()
                addParameters("$directory/")
            }
        }
        if (!result.success()) return null
        return result.output.joinToString("\n")
            .split('\u0000')
            .mapNotNull(::parseTreeLine)
    }

    private fun parseTreeLine(line: String): LocalTreeEntry? {
        val tab = line.indexOf('\t')
        if (tab <= 0) return null
        val meta = line.substring(0, tab).trim().split(' ')
        val path = line.substring(tab + 1)
        if (meta.size < 2 || path.isEmpty()) return null
        return when (meta[1]) {
            "blob" -> LocalTreeEntry(path, isDirectory = false)
            "tree" -> LocalTreeEntry(path, isDirectory = true)
            else -> null
        }
    }

    private fun isSafeRefAndPath(ref: String, path: String): Boolean =
        GitRefNames.isSha(ref) && GitRefNames.isSafeRepoPath(path)

    private fun run(command: GitCommand, configure: GitLineHandler.() -> Unit): GitCommandResult {
        val handler = GitLineHandler(project, repository.root, command).apply {
            setSilent(true)
            setStdoutSuppressed(true)
            configure()
        }
        return Git.getInstance().runCommand(handler)
    }
}
