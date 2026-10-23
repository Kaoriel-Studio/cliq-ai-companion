package com.cliq.plugin.review.settings

import com.cliq.plugin.review.connect.GitRemoteLocation
import com.cliq.plugin.review.connect.HostUrls
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread

class ReviewHostStore(private val project: Project?, shared: Boolean) {

    companion object {
        private const val SHARED_SCOPE = "shared"

        fun of(project: Project?): ReviewHostStore =
            ReviewHostStore(project, CliqReviewSettings.getInstance().shareHostsAcrossProjects)
    }

    private val settings = CliqReviewSettings.getInstance()
    private val projectHosts: CliqProjectReviewHosts? = project?.service<CliqProjectReviewHosts>()

    val isShared: Boolean = shared || projectHosts == null

    private val tokenScope: String
        get() = if (isShared) SHARED_SCOPE else project?.locationHash ?: SHARED_SCOPE

    fun hosts(): List<ReviewHost> = if (isShared) settings.hosts() else projectHosts!!.hosts()

    fun host(url: String): ReviewHost? = hosts().firstOrNull { it.url == url }

    fun findHost(remote: GitRemoteLocation): ReviewHost? =
        hosts().filter { HostUrls.matches(it.url, remote) }.maxByOrNull { HostUrls.pathPrefix(it.url).length }

    fun setHosts(hosts: List<ReviewHost>) {
        if (isShared) settings.setHosts(hosts) else projectHosts!!.setHosts(hosts)
    }

    fun upsertHost(host: ReviewHost) {
        setHosts(hosts().filter { it.url != host.url } + host)
    }

    fun markTokenStored(url: String, stored: Boolean) {
        setHosts(hosts().map { if (it.url == url) it.copy(tokenStored = stored) else it })
    }

    @RequiresBackgroundThread
    fun loadToken(host: ReviewHost): String? = ReviewTokens.load(host, tokenScope)

    @RequiresBackgroundThread
    fun storeToken(host: ReviewHost, token: String): Boolean {
        val stored = ReviewTokens.store(host, tokenScope, token)
        markTokenStored(host.url, stored)
        return stored
    }

    @RequiresBackgroundThread
    fun forgetToken(host: ReviewHost) {
        ReviewTokens.forget(host, tokenScope)
        markTokenStored(host.url, false)
    }

    @RequiresBackgroundThread
    fun adoptInto(target: ReviewHostStore) {
        val known = target.hosts().map { it.url }.toSet()
        hosts().filter { it.url !in known }.forEach { host ->
            target.upsertHost(host.copy(tokenStored = false))
            loadToken(host)?.let { target.storeToken(host, it) }
        }
    }
}
