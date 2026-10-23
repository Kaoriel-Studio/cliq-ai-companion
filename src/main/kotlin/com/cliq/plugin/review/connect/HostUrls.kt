package com.cliq.plugin.review.connect

import java.net.URI
import java.net.URISyntaxException

object HostUrls {

    fun normalize(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val uri = parse(trimmed) ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.split('/').any { it == ".." || it == "." }) return null
        return "$scheme://$host${portSuffix(uri.port)}$path"
    }

    fun suggestedUrl(remote: GitRemoteLocation): String {
        val port = if (remote.isHttps) remote.port else null
        return "https://${remote.host}${portSuffix(port ?: -1)}"
    }

    fun hostOf(url: String): String? = parse(url)?.host?.lowercase()

    fun pathPrefix(url: String): String = parse(url)?.rawPath.orEmpty().trim('/')

    fun matches(url: String, remote: GitRemoteLocation): Boolean {
        if (hostOf(url) != remote.host) return false
        val prefix = pathPrefix(url)
        return prefix.isEmpty() || remote.projectPath.startsWith("$prefix/")
    }

    fun projectPath(remote: GitRemoteLocation, url: String): String {
        val prefix = pathPrefix(url)
        if (prefix.isEmpty() || hostOf(url) != remote.host) return remote.projectPath
        val stripped = remote.projectPath.removePrefix("$prefix/")
        return GitRemoteParser.normalizeProjectPath(stripped) ?: remote.projectPath
    }

    private fun parse(url: String): URI? =
        try {
            URI(url)
        } catch (e: URISyntaxException) {
            null
        }

    private fun portSuffix(port: Int): String = if (port > 0) ":$port" else ""
}
