package com.cliq.plugin.review.collect.source

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

class DirectoryListings(
    private val reader: RepositoryReader,
    private val ref: String,
) {

    private val cache = ConcurrentHashMap<String, Optional<List<TreeEntry>>>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun list(directory: String): List<TreeEntry>? {
        cache[directory]?.let { return it.orElse(null) }
        val mutex = locks.computeIfAbsent(directory) { Mutex() }
        return mutex.withLock {
            cache[directory]?.let { return@withLock it.orElse(null) }
            val listing = reader.list(ref, directory)
            cache[directory] = Optional.ofNullable(listing)
            listing
        }
    }

    suspend fun fileExists(path: String): Boolean {
        return list(parentOf(path))?.any { !it.isDirectory && it.path == path } == true
    }

    companion object {
        fun parentOf(path: String): String = path.substringBeforeLast('/', "")
    }
}
