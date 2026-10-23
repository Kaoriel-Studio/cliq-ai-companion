package com.cliq.plugin.review.collect

import com.cliq.plugin.review.collect.filter.SecretRedactor
import com.cliq.plugin.review.collect.render.ReviewContextRenderer
import com.cliq.plugin.review.collect.render.XmlTextTest
import com.cliq.plugin.review.http.FakeApiTransport
import com.cliq.plugin.review.model.ChangeStatus
import com.cliq.plugin.review.model.DiffSource
import com.cliq.plugin.review.model.RelatedKind
import com.cliq.plugin.review.provider.ReviewSession
import com.cliq.plugin.review.provider.github.GitHubApi
import com.cliq.plugin.review.provider.github.GitHubProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubReviewCollectionTest {

    private val mergeBase = "a".repeat(40)
    private val targetTip = "c".repeat(40)
    private val head = "b".repeat(40)
    private val token = "ghp_" + "t".repeat(36)
    private val repo = "octo/app"
    private val servicePath = "src/main/kotlin/app/UserService.kt"
    private val oldService = "package app\n\nclass UserService {\n    fun find() = null\n}\n"
    private val newService = "package app\n\nclass UserService {\n    fun find(id: Int) = repo.load(id)\n    private val apiKey = \"$token\"\n}\n"
    private val logUrl = "https://pipelines.actions.githubusercontent.com/logs/77?sig=abc"

    private fun fakeGitHub() = FakeApiTransport(apiRoot = "https://api.github.com/", token = token).apply {
        json("repos/octo/app", """{"id": 9, "full_name": "$repo", "html_url": "https://github.com/$repo"}""")
        json(
            "repos/octo/app/pulls/5",
            """
            {"number": 5, "title": "Add user lookup", "body": "Staging uses $token", "state": "open", "draft": false,
             "html_url": "https://github.com/$repo/pull/5", "user": {"login": "bob"},
             "head": {"ref": "feature/lookup", "sha": "$head"}, "base": {"ref": "main", "sha": "$targetTip"},
             "labels": [{"name": "backend"}]}
            """,
        )
        json("repos/octo/app/compare/$targetTip...$head", """{"merge_base_commit": {"sha": "$mergeBase"}}""")
        pagedJson(
            "repos/octo/app/pulls/5/files",
            listOf(
                """
                [{"filename": "$servicePath", "status": "modified", "patch": "@@ -3,3 +3,4 @@\n class UserService {\n-    fun find() = null\n+    fun find(id: Int) = repo.load(id)\n+    private val apiKey = \"$token\"\n }"},
                 {"filename": "src/main/kotlin/app/Lookup.kt", "status": "added", "patch": "@@ -0,0 +1 @@\n+class Lookup"}]
                """,
                """
                [{"filename": "src/main/kotlin/app/Renamed.kt", "previous_filename": "src/main/kotlin/app/Old.kt", "status": "renamed", "patch": "@@ -1 +1 @@\n-object Old\n+object Renamed"},
                 {"filename": ".env", "status": "modified", "patch": "@@ -1 +1 @@\n-A=1\n+A=2"},
                 {"filename": "huge/Generated.kt", "status": "modified"}]
                """,
            ),
            FakeApiTransport::gitHubPaging,
        )
        pagedJson(
            "repos/octo/app/issues/5/comments",
            listOf("""[{"id": 1, "body": "LGTM overall", "user": {"login": "carol"}, "created_at": "2024-01-01T10:00:00Z"}]"""),
            FakeApiTransport::gitHubPaging,
        )
        pagedJson(
            "repos/octo/app/pulls/5/comments",
            listOf(
                """
                [{"id": 10, "path": "$servicePath", "line": 4, "body": "Should this handle null ids?", "user": {"login": "dave"}, "created_at": "2024-01-01T11:00:00Z"},
                 {"id": 11, "in_reply_to_id": 10, "path": "$servicePath", "line": 4, "body": "Handled in the next commit", "user": {"login": "bob"}, "created_at": "2024-01-01T12:00:00Z"}]
                """,
            ),
            FakeApiTransport::gitHubPaging,
        )
        pagedJson(
            "repos/octo/app/pulls/5/reviews",
            listOf(
                """
                [{"id": 20, "state": "CHANGES_REQUESTED", "body": "", "user": {"login": "dave"}, "submitted_at": "2024-01-01T11:30:00Z"},
                 {"id": 21, "state": "COMMENTED", "body": "", "user": {"login": "eve"}, "submitted_at": "2024-01-01T11:40:00Z"}]
                """,
            ),
            FakeApiTransport::gitHubPaging,
        )
        pagedJson(
            "repos/octo/app/pulls/5/commits",
            listOf("""[{"sha": "$head", "commit": {"message": "Add lookup\n\nWires the repository", "author": {"name": "Bob", "date": "2024-01-01T09:00:00Z"}}}]"""),
            FakeApiTransport::gitHubPaging,
        )
        json(
            "repos/octo/app/commits/$head/check-runs",
            """
            {"total_count": 2, "check_runs": [
              {"id": 77, "name": "test", "status": "completed", "conclusion": "failure", "app": {"slug": "github-actions"}},
              {"id": 78, "name": "lint", "status": "completed", "conclusion": "success", "app": {"slug": "github-actions"}}]}
            """,
        )
        json("repos/octo/app/commits/$head/status", """{"state": "success", "statuses": [{"context": "ci/legacy", "state": "success"}]}""")
        status("repos/octo/app/actions/jobs/77/logs", 302, "", mapOf("Location" to logUrl))
        onExternal { url ->
            if (url == logUrl) {
                FakeApiTransport.Reply(200, "2024-01-01T10:00:00.1234567Z ##[error]FAILED UserServiceTest > find\n".toByteArray())
            } else {
                null
            }
        }
        on { request ->
            if (request.path != "search/code") return@on null
            FakeApiTransport.Reply(200, """{"items": [{"path": "src/main/kotlin/app/UserController.kt"}]}""".toByteArray())
        }
        on { request ->
            if (request.query["ref"] != head || request.accept == GitHubApi.RAW_MEDIA_TYPE) return@on null
            val listing = when (request.path) {
                "repos/octo/app/contents" -> """[{"name": "README.md", "path": "README.md", "type": "file"}, {"name": ".github", "path": ".github", "type": "dir"}, {"name": "src", "path": "src", "type": "dir"}]"""
                "repos/octo/app/contents/.github/workflows" -> """[{"name": "ci.yml", "path": ".github/workflows/ci.yml", "type": "file"}]"""
                "repos/octo/app/contents/src/main/kotlin/app" -> """[
                    {"name": "UserService.kt", "path": "$servicePath", "type": "file"},
                    {"name": "UserController.kt", "path": "src/main/kotlin/app/UserController.kt", "type": "file"}]"""
                "repos/octo/app/contents/src/test/kotlin/app" -> """[{"name": "UserServiceTest.kt", "path": "src/test/kotlin/app/UserServiceTest.kt", "type": "file"}]"""
                else -> return@on null
            }
            FakeApiTransport.Reply(200, listing.toByteArray())
        }
        gitHubFiles(
            repo,
            head,
            mapOf(
                servicePath to newService,
                "src/main/kotlin/app/Lookup.kt" to "class Lookup\n",
                "src/main/kotlin/app/Renamed.kt" to "object Renamed\n",
                "src/main/kotlin/app/UserController.kt" to "class UserController(private val service: UserService)\n",
                "src/test/kotlin/app/UserServiceTest.kt" to "class UserServiceTest\n",
                "README.md" to "# App\n",
                ".github/workflows/ci.yml" to "on: push\njobs:\n  test:\n    runs-on: ubuntu-latest\n",
            ),
        )
        gitHubFiles(repo, mergeBase, mapOf(servicePath to oldService, "src/main/kotlin/app/Old.kt" to "object Old\n"))
    }

    private fun session(transport: FakeApiTransport): ReviewSession = runBlocking { GitHubProvider.open(transport, repo) }

    @Test
    fun usesMergeBaseInsteadOfTargetTip() {
        val request = runBlocking { session(fakeGitHub()).request(5) }
        assertEquals(mergeBase, request.refs.baseSha)
        assertEquals(targetTip, request.refs.startSha)
        assertEquals(head, request.refs.headSha)
        assertEquals("feature/lookup", request.sourceBranch)
        assertEquals(listOf("backend"), request.labels)
    }

    @Test
    fun collectsPullRequestThroughTheSharedPipeline() {
        val transport = fakeGitHub()
        val collected = collectWith(session(transport), 5, testOptions(), SecretRedactor(listOf(token)))
        val sections = collected.sections.associateBy { it.file.newPath }

        assertEquals(5, sections.size)
        assertEquals("sensitive", sections.getValue(".env").skipReason)

        val renamed = sections.getValue("src/main/kotlin/app/Renamed.kt")
        assertEquals(ChangeStatus.RENAMED, renamed.file.status)
        assertEquals("src/main/kotlin/app/Old.kt", renamed.file.oldPath)
        assertEquals(DiffSource.API, renamed.diffSource)

        val service = sections.getValue(servicePath)
        assertEquals(DiffSource.API, service.diffSource)
        assertFalse(service.contentAfter.orEmpty().contains(token))

        val missingPatch = sections.getValue("huge/Generated.kt")
        assertNull(missingPatch.diff)
        assertTrue(missingPatch.notes.any { it.contains("diff unavailable") })

        assertEquals(
            mapOf(
                "src/test/kotlin/app/UserServiceTest.kt" to RelatedKind.TEST,
                "src/main/kotlin/app/UserController.kt" to RelatedKind.DEPENDENT,
            ),
            collected.related.getValue(servicePath).associate { it.path to it.kind },
        )
        assertEquals(listOf("README.md", ".github/workflows/ci.yml"), collected.projectContext.configs.map { it.path })

        val discussions = collected.metadata.discussions
        assertEquals(3, discussions.size)
        assertEquals("LGTM overall", discussions[0].notes.single().body)
        assertEquals(servicePath to 4, discussions[1].path to discussions[1].line)
        assertEquals(listOf("dave", "bob"), discussions[1].notes.map { it.author })
        assertEquals("[CHANGES_REQUESTED]", discussions[2].notes.single().body)

        val ci = assertNotNull(collected.metadata.ci)
        assertEquals("failed", ci.status)
        assertEquals(listOf("test", "lint", "ci/legacy"), ci.jobs.map { it.name })
        assertEquals("##[error]FAILED UserServiceTest > find", ci.failedJobLogs.single().tail)
        assertEquals(listOf(logUrl), transport.externalRequests.toList())

        val rawRequests = transport.requests.filter { it.path.contains("/contents/") && it.accept == GitHubApi.RAW_MEDIA_TYPE }
        assertTrue(rawRequests.isNotEmpty())
    }

    @Test
    fun rendersPullRequestFilesWithGitHubNaming() {
        val redactor = SecretRedactor(listOf(token))
        val collected = collectWith(session(fakeGitHub()), 5, testOptions(), redactor)
        val rendered = ReviewContextRenderer(testOptions().chunkBudget, redactor).render(collected)

        (listOf(rendered.meta, rendered.project) + rendered.chunks.map { it.xml }).forEach { xml ->
            XmlTextTest.parse(xml)
            assertFalse(xml.contains(token))
        }
        assertEquals("pr_5_meta.xml", rendered.names.meta)
        assertEquals("pr_5_project.xml", rendered.names.project)
        assertTrue(rendered.chunks.all { it.fileName.startsWith("pr_5_chunk_") })
        assertTrue(rendered.meta.contains("""kind="pull_request""""))
        assertTrue(rendered.meta.contains("<provider>GitHub</provider>"))
        assertTrue(rendered.meta.contains("<reference>#5</reference>"))
        assertTrue(rendered.meta.contains("<base_sha>$mergeBase</base_sha>"))
        assertTrue(rendered.project.contains("runs-on: ubuntu-latest"))
    }

    @Test
    fun buildsWideContextDiffAgainstTheMergeBase() {
        val collected = collectWith(session(fakeGitHub()), 5, testOptions(contextLines = 20), SecretRedactor(listOf(token)))
        val service = collected.sections.single { it.file.newPath == servicePath }
        assertEquals(DiffSource.IDE, service.diffSource)
        assertTrue(service.diff.orEmpty().contains("\n-    fun find() = null\n"))
    }
}
