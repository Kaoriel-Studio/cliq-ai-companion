package com.cliq.plugin.review.git

object GitRefNames {

    private val SHA = Regex("^[0-9a-fA-F]{7,64}$")
    private val FORBIDDEN_BRANCH_CHARS = setOf(' ', '~', '^', ':', '?', '*', '[', '\\')
    private const val MAX_BRANCH_LENGTH = 255
    private const val MAX_REPO_PATH_LENGTH = 4096

    fun isSha(value: String?): Boolean = value != null && SHA.matches(value)

    fun isSafeBranch(name: String?): Boolean {
        if (name.isNullOrEmpty() || name.length > MAX_BRANCH_LENGTH) return false
        if (name.startsWith('-') || name.startsWith('/') || name.endsWith('/') || name.endsWith('.')) return false
        if (name.endsWith(".lock") || name.contains("..") || name.contains("//") || name.contains("@{")) return false
        return name.none { it.isISOControl() || it in FORBIDDEN_BRANCH_CHARS }
    }

    fun isSafeRepoPath(path: String?): Boolean {
        if (path.isNullOrEmpty() || path.length > MAX_REPO_PATH_LENGTH) return false
        if (path.startsWith('/') || path.startsWith('-') || path.any { it.isISOControl() }) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    fun branchRefspec(branch: String): String {
        require(isSafeBranch(branch)) { "Unsafe branch name" }
        return "refs/heads/$branch"
    }
}
