package com.cliq.plugin.review.connect

import com.cliq.plugin.review.provider.ProviderKind
import com.cliq.plugin.review.provider.github.GitHubProvider
import com.cliq.plugin.review.provider.gitlab.GitLabProvider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostUrlsTest {

    private val sshRemote = GitRemoteLocation("gitlab.example.com", null, "group/project", isHttps = false)

    @Test
    fun normalizesHostUrls() {
        assertEquals("https://gitlab.example.com", HostUrls.normalize(" https://GitLab.example.com/ "))
        assertEquals("http://10.0.0.5:8080/gitlab", HostUrls.normalize("http://10.0.0.5:8080/gitlab/"))
        assertNull(HostUrls.normalize("ftp://gitlab.example.com"))
        assertNull(HostUrls.normalize("https://user:pass@gitlab.example.com"))
        assertNull(HostUrls.normalize("https://gitlab.example.com/?x=1"))
        assertNull(HostUrls.normalize("https://gitlab.example.com/../admin"))
        assertNull(HostUrls.normalize(""))
    }

    @Test
    fun suggestsHttpsUrlForRemotes() {
        assertEquals("https://gitlab.example.com", HostUrls.suggestedUrl(sshRemote.copy(port = 2222)))
        assertEquals(
            "https://ghe.corp:8443",
            HostUrls.suggestedUrl(GitRemoteLocation("ghe.corp", 8443, "org/repo", isHttps = true)),
        )
    }

    @Test
    fun matchesRemotesByHostAndPathPrefix() {
        assertTrue(HostUrls.matches("https://gitlab.example.com", sshRemote))
        assertFalse(HostUrls.matches("https://github.com", sshRemote))
        val prefixed = GitRemoteLocation("host.example.com", null, "gitlab/group/project", isHttps = true)
        assertTrue(HostUrls.matches("https://host.example.com/gitlab", prefixed))
        assertFalse(HostUrls.matches("https://host.example.com/other", prefixed))
    }

    @Test
    fun stripsPathPrefixFromProjectPath() {
        val remote = GitRemoteLocation("host.example.com", null, "gitlab/group/project", isHttps = true)
        assertEquals("group/project", HostUrls.projectPath(remote, "https://host.example.com/gitlab"))
        assertEquals("gitlab/group/project", HostUrls.projectPath(remote, "https://other.example.com/gitlab"))
    }

    @Test
    fun guessesProviderFromHost() {
        assertEquals(ProviderKind.GITHUB, ProviderKind.guess("github.com"))
        assertEquals(ProviderKind.GITHUB, ProviderKind.guess("github.corp.example"))
        assertEquals(ProviderKind.GITLAB, ProviderKind.guess("gitlab.example.com"))
        assertEquals("#12", ProviderKind.GITHUB.reference(12))
        assertEquals("!12", ProviderKind.GITLAB.reference(12))
    }

    @Test
    fun derivesApiRootsAndProjectReferences() {
        assertEquals("https://api.github.com/", GitHubProvider.apiRoot("https://github.com"))
        assertEquals("https://ghe.corp/api/v3/", GitHubProvider.apiRoot("https://ghe.corp"))
        assertEquals("https://gitlab.corp/gitlab/api/v4/", GitLabProvider.apiRoot("https://gitlab.corp/gitlab"))
        assertEquals("octo/app", GitHubProvider.normalizeProjectReference("/octo/app.git"))
        assertNull(GitHubProvider.normalizeProjectReference("org/team/app"))
        assertNull(GitHubProvider.normalizeProjectReference("123"))
        assertEquals("org/team/app", GitLabProvider.normalizeProjectReference("org/team/app"))
        assertEquals("123", GitLabProvider.normalizeProjectReference("123"))
    }
}
