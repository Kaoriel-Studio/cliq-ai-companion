package com.cliq.plugin.review.provider.gitlab

import com.cliq.plugin.review.http.FakeApiTransport
import com.cliq.plugin.review.http.RawFetch
import com.cliq.plugin.review.http.ReviewApiException
import com.cliq.plugin.review.http.ReviewHttpException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitLabApiTest {

    @Test
    fun encodesProjectPathAsSingleSegment() = runBlocking {
        val transport = FakeApiTransport()
        transport.json("projects/group%2Fsub%2Fapp", """{"id": 5, "path_with_namespace": "group/sub/app", "unknown": true}""")
        val project = GitLabApi(transport).project("group/sub/app")
        assertEquals(5, project.id)
        assertEquals("group/sub/app", project.pathWithNamespace)
    }

    @Test
    fun followsPaginationHeaders() = runBlocking {
        val transport = FakeApiTransport()
        transport.pagedJson(
            "projects/1/merge_requests/2/commits",
            listOf("""[{"id":"a","title":"one"}]""", """[{"id":"b","title":"two"}]""", """[{"id":"c","title":"three"}]"""),
            FakeApiTransport::gitLabPaging,
        )
        assertEquals(listOf("one", "two", "three"), GitLabApi(transport).commits(1, 2).map { it.title })
    }

    @Test
    fun fallsBackToChangesEndpointOnOlderGitLab() = runBlocking {
        val transport = FakeApiTransport()
        transport.json(
            "projects/1/merge_requests/2/changes",
            """{"changes":[{"old_path":"a.kt","new_path":"a.kt","diff":"@@ -1 +1 @@\n-a\n+b","too_large":null}]}""",
        )
        val diffs = GitLabApi(transport).mergeRequestDiffs(1, 2)
        assertEquals(1, diffs.size)
        assertFalse(diffs.single().tooLarge)
    }

    @Test
    fun mapsErrorsWithoutLeakingTheToken() = runBlocking {
        val transport = FakeApiTransport(token = "glpat-secret-value-123456")
        transport.status("user", 401, """{"message":"401 Unauthorized glpat-secret-value-123456\u001b[31m"}""")
        val error = assertFailsWith<ReviewHttpException> { GitLabApi(transport).currentUser() }
        assertEquals(401, error.status)
        val message = error.message.orEmpty()
        assertTrue(message.startsWith("GitLab request to user failed"))
        assertFalse(message.contains("glpat-secret-value-123456"))
        assertFalse(message.contains('\u001b'))
    }

    @Test
    fun reportsRawFileStates() = runBlocking {
        val transport = FakeApiTransport()
        transport.on { request ->
            if (request.query["ref"] != "abc") return@on null
            when (request.path) {
                "projects/1/repository/files/src%2FA.kt/raw" -> FakeApiTransport.Reply(200, "class A".toByteArray())
                "projects/1/repository/files/big.txt/raw" -> FakeApiTransport.Reply(200, "x".repeat(100).toByteArray())
                else -> null
            }
        }
        val api = GitLabApi(transport)
        assertIs<RawFetch.Found>(api.rawFile(1, "src/A.kt", "abc", 1_000))
        assertIs<RawFetch.TooLarge>(api.rawFile(1, "big.txt", "abc", 10))
        assertIs<RawFetch.NotFound>(api.rawFile(1, "missing.kt", "abc", 1_000))
        Unit
    }

    @Test
    fun treatsMissingTreeAsAbsentDirectory() = runBlocking {
        assertNull(GitLabApi(FakeApiTransport()).tree(1, "nope", "abc"))
    }

    @Test
    fun rejectsUnexpectedPayloads() = runBlocking {
        val transport = FakeApiTransport()
        transport.json("projects/1/merge_requests/3", "<html>proxy login</html>")
        assertFailsWith<ReviewApiException> { GitLabApi(transport).mergeRequest(1, 3) }
        Unit
    }

    @Test
    fun buildsApiRootFromWebUrl() {
        assertEquals("https://gitlab.example.com/gitlab/api/v4/", GitLabProvider.apiRoot("https://gitlab.example.com/gitlab"))
    }
}
