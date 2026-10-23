package com.cliq.plugin.review.connect

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitRemoteParserTest {

    @Test
    fun parsesScpLikeSshRemote() {
        val remote = GitRemoteParser.parse("git@gitlab.example.com:group/sub/project.git")
        assertEquals(GitRemoteLocation("gitlab.example.com", null, "group/sub/project", isHttps = false), remote)
    }

    @Test
    fun parsesSshUrlWithCustomPort() {
        val remote = GitRemoteParser.parse("ssh://git@gitlab.example.com:2222/group/project.git")
        assertEquals(GitRemoteLocation("gitlab.example.com", 2222, "group/project", isHttps = false), remote)
    }

    @Test
    fun parsesHttpsRemoteAndDropsCredentials() {
        val remote = GitRemoteParser.parse("https://user:secret@GitLab.Example.com:8443/group/project.git/")
        assertEquals(GitRemoteLocation("gitlab.example.com", 8443, "group/project", isHttps = true), remote)
    }

    @Test
    fun parsesHttpsRemoteWithoutGitSuffix() {
        assertEquals("group/project", GitRemoteParser.parse("https://gitlab.com/group/project")?.projectPath)
    }

    @Test
    fun rejectsUnsupportedOrMalformedRemotes() {
        assertNull(GitRemoteParser.parse("file:///srv/repos/project.git"))
        assertNull(GitRemoteParser.parse("C:/repos/project"))
        assertNull(GitRemoteParser.parse("https://gitlab.com/project"))
        assertNull(GitRemoteParser.parse("https://gitlab.com/group/../etc"))
        assertNull(GitRemoteParser.parse("git@host:group/pro ject.git"))
        assertNull(GitRemoteParser.parse(""))
    }

    @Test
    fun normalizesProjectReferences() {
        assertEquals("123", GitRemoteParser.normalizeProjectReference(" 00123 "))
        assertEquals("group/project", GitRemoteParser.normalizeProjectReference("/group/project.git/"))
        assertNull(GitRemoteParser.normalizeProjectReference("0"))
        assertNull(GitRemoteParser.normalizeProjectReference("group/-bad/../x"))
    }
}
