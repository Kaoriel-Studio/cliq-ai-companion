package com.cliq.plugin.review.collect.source

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface FileContent {
    class Text(val value: String) : FileContent
    data object Binary : FileContent
    data object TooLarge : FileContent
    data object Missing : FileContent
    data object Unavailable : FileContent
}

data class TreeEntry(
    val path: String,
    val isDirectory: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')
}

interface RepositoryReader {

    suspend fun content(ref: String, path: String, maxBytes: Int): FileContent

    suspend fun list(ref: String, directory: String): List<TreeEntry>?
}

object ContentDecoder {

    private const val BINARY_PROBE_BYTES = 8_000
    private const val BYTE_ORDER_MARK = "﻿"

    fun decode(bytes: ByteArray, maxBytes: Int): FileContent {
        if (bytes.size > maxBytes) return FileContent.TooLarge
        val probeLength = minOf(bytes.size, BINARY_PROBE_BYTES)
        for (index in 0 until probeLength) {
            if (bytes[index] == 0.toByte()) return FileContent.Binary
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return try {
            FileContent.Text(decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix(BYTE_ORDER_MARK))
        } catch (e: CharacterCodingException) {
            FileContent.Binary
        }
    }
}

class LayeredReader(
    private val primary: RepositoryReader,
    private val fallback: RepositoryReader,
) : RepositoryReader {

    override suspend fun content(ref: String, path: String, maxBytes: Int): FileContent =
        when (val result = primary.content(ref, path, maxBytes)) {
            FileContent.Unavailable -> fallback.content(ref, path, maxBytes)
            else -> result
        }

    override suspend fun list(ref: String, directory: String): List<TreeEntry>? =
        primary.list(ref, directory) ?: fallback.list(ref, directory)
}
