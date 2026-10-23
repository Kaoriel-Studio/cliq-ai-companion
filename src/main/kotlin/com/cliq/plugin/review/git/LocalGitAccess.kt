package com.cliq.plugin.review.git

data class LocalGitRemote(
    val name: String,
    val urls: List<String>,
)

data class LocalTreeEntry(
    val path: String,
    val isDirectory: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')
}

interface LocalGitRepository {

    val rootPath: String

    val remotes: List<LocalGitRemote>

    val currentBranch: String?

    fun hasCommit(sha: String): Boolean

    fun fetch(remoteName: String, refspec: String): Boolean

    fun diff(baseSha: String, headSha: String, oldPath: String, newPath: String, contextLines: Int): String?

    fun objectSize(ref: String, path: String): Long?

    fun fileContent(ref: String, path: String): ByteArray?

    fun listDirectory(ref: String, directory: String): List<LocalTreeEntry>?
}

interface LocalGitAccess {

    fun repositories(): List<LocalGitRepository>
}
