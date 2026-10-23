package com.cliq.plugin.review.connect

import java.net.URI
import java.net.URISyntaxException

data class GitRemoteLocation(
    val host: String,
    val port: Int?,
    val projectPath: String,
    val isHttps: Boolean,
)

object GitRemoteParser {

    private val SUPPORTED_SCHEMES = setOf("https", "http", "ssh", "git+ssh", "ssh+git")
    private val SCP_LIKE = Regex("^(?:[^@\\s/]+@)?([^@\\s/:]{2,}):(?!/)(.+)$")
    private val PATH_SEGMENT = Regex("^[A-Za-z0-9_.][A-Za-z0-9_.\\-]*$")
    private const val NUMERIC_ID_MAX_LENGTH = 18

    fun parse(url: String): GitRemoteLocation? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        return if ("://" in trimmed) parseUri(trimmed) else parseScpLike(trimmed)
    }

    fun normalizeProjectPath(raw: String): String? {
        val path = raw.trim().trim('/').removeSuffix(".git").trimEnd('/')
        val segments = path.split('/')
        if (segments.size < 2) return null
        if (segments.any { it == "." || it == ".." || !PATH_SEGMENT.matches(it) }) return null
        return segments.joinToString("/")
    }

    fun normalizeProjectReference(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isNotEmpty() && trimmed.length <= NUMERIC_ID_MAX_LENGTH && trimmed.all(Char::isDigit)) {
            return trimmed.trimStart('0').ifEmpty { null }
        }
        return normalizeProjectPath(trimmed)
    }

    private fun parseUri(raw: String): GitRemoteLocation? {
        val uri = try {
            URI(raw)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in SUPPORTED_SCHEMES) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val projectPath = normalizeProjectPath(uri.path.orEmpty()) ?: return null
        return GitRemoteLocation(host, uri.port.takeIf { it > 0 }, projectPath, isHttps = scheme == "https")
    }

    private fun parseScpLike(raw: String): GitRemoteLocation? {
        val match = SCP_LIKE.matchEntire(raw) ?: return null
        val host = match.groupValues[1].lowercase()
        val projectPath = normalizeProjectPath(match.groupValues[2]) ?: return null
        return GitRemoteLocation(host, null, projectPath, isHttps = false)
    }
}
