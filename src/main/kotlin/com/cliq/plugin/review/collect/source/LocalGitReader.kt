package com.cliq.plugin.review.collect.source

import com.cliq.plugin.review.git.LocalGitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class LocalGitReader(
    private val repository: LocalGitRepository,
    maxParallelProcesses: Int = DEFAULT_MAX_PARALLEL_PROCESSES,
) : RepositoryReader {

    companion object {
        const val DEFAULT_MAX_PARALLEL_PROCESSES = 4
    }

    private val permits = Semaphore(maxParallelProcesses)

    override suspend fun content(ref: String, path: String, maxBytes: Int): FileContent =
        onGitThread {
            val size = repository.objectSize(ref, path) ?: return@onGitThread FileContent.Missing
            if (size > maxBytes) return@onGitThread FileContent.TooLarge
            val bytes = repository.fileContent(ref, path) ?: return@onGitThread FileContent.Unavailable
            ContentDecoder.decode(bytes, maxBytes)
        }

    override suspend fun list(ref: String, directory: String): List<TreeEntry>? =
        onGitThread {
            repository.listDirectory(ref, directory)?.map { TreeEntry(it.path, it.isDirectory) }
        }

    suspend fun diff(baseSha: String, headSha: String, oldPath: String, newPath: String, contextLines: Int): String? =
        onGitThread { repository.diff(baseSha, headSha, oldPath, newPath, contextLines) }

    private suspend fun <T> onGitThread(block: () -> T): T =
        permits.withPermit { withContext(Dispatchers.IO) { block() } }
}
