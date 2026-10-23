package com.cliq.plugin.review.collect

import com.cliq.plugin.review.collect.budget.ChunkBudget
import com.cliq.plugin.review.collect.budget.TokenEstimator
import com.cliq.plugin.review.collect.filter.SecretRedactor
import com.cliq.plugin.review.collect.render.ReviewContextRenderer
import com.cliq.plugin.review.collect.render.XmlTextTest
import com.cliq.plugin.review.http.FakeApiTransport
import com.cliq.plugin.review.model.DiffSource
import com.cliq.plugin.review.model.RelatedKind
import com.cliq.plugin.review.model.ReviewContextException
import com.cliq.plugin.review.provider.ReviewSession
import com.cliq.plugin.review.provider.gitlab.GitLabProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitLabReviewCollectionTest {

    private val base = "a".repeat(40)
    private val head = "b".repeat(40)
    private val token = "glpat-collector-secret-0001"
    private val servicePath = "src/main/kotlin/app/UserService.kt"
    private val oldService = "package app\n\nclass UserService {\n    fun find() = null\n}\n"
    private val newService = "package app\n\nclass UserService {\n    fun find(id: Int) = repo.load(id)\n    private val apiKey = \"$token\"\n}\n"

    private fun fakeGitLab(serviceAtHead: String = newService) = FakeApiTransport(token = token).apply {
        json("projects/group%2Fapp", """{"id": 1, "path_with_namespace": "group/app", "web_url": "https://gitlab.example.com/group/app"}""")
        json(
            "projects/1/merge_requests/7",
            """
            {"iid": 7, "title": "Add user lookup", "description": "Configured with $token for staging",
             "state": "opened", "source_branch": "feature/lookup", "target_branch": "main", "labels": ["backend"],
             "web_url": "https://gitlab.example.com/group/app/-/merge_requests/7", "author": {"username": "bob"},
             "diff_refs": {"base_sha": "$base", "head_sha": "$head", "start_sha": "$base"},
             "head_pipeline": {"id": 99, "status": "failed", "ref": "feature/lookup", "web_url": "https://gitlab.example.com/p/99"}}
            """,
        )
        pagedJson(
            "projects/1/merge_requests/7/diffs",
            listOf(
                """
                [{"old_path": "$servicePath", "new_path": "$servicePath", "diff": "@@ -3,3 +3,4 @@\n class UserService {\n-    fun find() = null\n+    fun find(id: Int) = repo.load(id)\n+    private val apiKey = \"$token\"\n }"},
                 {"old_path": "src/main/kotlin/app/Lookup.kt", "new_path": "src/main/kotlin/app/Lookup.kt", "new_file": true, "diff": "@@ -0,0 +1 @@\n+class Lookup"}]
                """,
                """
                [{"old_path": ".env", "new_path": ".env", "diff": "@@ -1 +1 @@\n-A=1\n+A=2"},
                 {"old_path": "docs/logo.png", "new_path": "docs/logo.png", "new_file": true, "diff": ""},
                 {"old_path": "src/main/kotlin/app/Legacy.kt", "new_path": "src/main/kotlin/app/Legacy.kt", "deleted_file": true, "diff": "@@ -1 +0,0 @@\n-class Legacy"}]
                """,
            ),
            FakeApiTransport::gitLabPaging,
        )
        pagedJson(
            "projects/1/merge_requests/7/discussions",
            listOf(
                """
                [{"id": "d1", "notes": [{"id": 1, "body": "added 1 commit", "system": true}]},
                 {"id": "d2", "notes": [{"id": 2, "body": "Should this handle null ids?", "author": {"username": "carol"},
                   "resolvable": true, "resolved": false, "position": {"new_path": "$servicePath", "new_line": 4}}]}]
                """,
            ),
            FakeApiTransport::gitLabPaging,
        )
        pagedJson(
            "projects/1/merge_requests/7/commits",
            listOf("""[{"id": "$head", "short_id": "bbbbbbbb", "title": "Add lookup", "message": "Add lookup\n\nWires the repository", "author_name": "Bob"}]"""),
            FakeApiTransport::gitLabPaging,
        )
        pagedJson(
            "projects/1/pipelines/99/jobs",
            listOf("""[{"id": 5, "name": "test", "stage": "test", "status": "failed"}, {"id": 6, "name": "lint", "stage": "test", "status": "success"}]"""),
            FakeApiTransport::gitLabPaging,
        )
        on { request ->
            if (request.path == "projects/1/jobs/5/trace") {
                FakeApiTransport.Reply(200, "\u001B[31mFAILED UserServiceTest > find\u001B[0m\n".toByteArray())
            } else {
                null
            }
        }
        on { request ->
            if (request.path != "projects/1/search" || request.query["scope"] != "blobs") return@on null
            FakeApiTransport.Reply(200, """[{"path": "src/main/kotlin/app/UserController.kt"}]""".toByteArray())
        }
        on { request ->
            if (request.path != "projects/1/repository/tree" || request.query["ref"] != head) return@on null
            val listing = when (request.query["path"].orEmpty()) {
                "" -> """[{"name": "README.md", "path": "README.md", "type": "blob"}, {"name": "build.gradle.kts", "path": "build.gradle.kts", "type": "blob"}, {"name": "src", "path": "src", "type": "tree"}]"""
                "src/main/kotlin/app" -> """[
                    {"name": "UserService.kt", "path": "$servicePath", "type": "blob"},
                    {"name": "Lookup.kt", "path": "src/main/kotlin/app/Lookup.kt", "type": "blob"},
                    {"name": "UserController.kt", "path": "src/main/kotlin/app/UserController.kt", "type": "blob"},
                    {"name": "Helper.kt", "path": "src/main/kotlin/app/Helper.kt", "type": "blob"}]"""
                "src/test/kotlin/app" -> """[{"name": "UserServiceTest.kt", "path": "src/test/kotlin/app/UserServiceTest.kt", "type": "blob"}]"""
                else -> return@on null
            }
            FakeApiTransport.Reply(200, listing.toByteArray())
        }
        gitLabFiles(
            1,
            head,
            mapOf(
                servicePath to serviceAtHead,
                "src/main/kotlin/app/Lookup.kt" to "class Lookup\n",
                "src/main/kotlin/app/UserController.kt" to "class UserController(private val service: UserService)\n",
                "src/main/kotlin/app/Helper.kt" to "object Helper\n",
                "src/test/kotlin/app/UserServiceTest.kt" to "class UserServiceTest\n",
                "README.md" to "# App\n",
                "build.gradle.kts" to "plugins { kotlin(\"jvm\") }\n",
            ),
        )
        gitLabFiles(1, base, mapOf(servicePath to oldService, "src/main/kotlin/app/Legacy.kt" to "class Legacy\n"))
    }

    private fun session(transport: FakeApiTransport): ReviewSession = runBlocking { GitLabProvider.open(transport, "group/app") }

    @Test
    fun collectsSectionsRelatedFilesAndProjectContext() {
        val collected = collectWith(session(fakeGitLab()), 7, testOptions(), SecretRedactor(listOf(token)))
        val sections = collected.sections.associateBy { it.file.newPath }

        assertEquals(5, sections.size)
        assertEquals("sensitive", sections.getValue(".env").skipReason)
        assertEquals("excluded_pattern", sections.getValue("docs/logo.png").skipReason)

        val service = sections.getValue(servicePath)
        assertEquals(DiffSource.API, service.diffSource)
        assertTrue(service.diff.orEmpty().startsWith("--- a/$servicePath\n+++ b/$servicePath\n@@"))
        assertFalse(service.contentAfter.orEmpty().contains(token))
        assertNull(service.contentBefore)

        val added = sections.getValue("src/main/kotlin/app/Lookup.kt")
        assertNull(added.diff)
        assertEquals("class Lookup\n", added.contentAfter)

        val deleted = sections.getValue("src/main/kotlin/app/Legacy.kt")
        assertNotNull(deleted.diff)
        assertNull(deleted.contentAfter)

        assertEquals(
            mapOf(
                "src/test/kotlin/app/UserServiceTest.kt" to RelatedKind.TEST,
                "src/main/kotlin/app/UserController.kt" to RelatedKind.DEPENDENT,
                "src/main/kotlin/app/Helper.kt" to RelatedKind.SIBLING,
            ),
            collected.related.getValue(servicePath).associate { it.path to it.kind },
        )
        assertEquals(listOf("README.md", "build.gradle.kts"), collected.projectContext.configs.map { it.path })

        val ci = assertNotNull(collected.metadata.ci)
        assertEquals("failed", ci.status)
        assertEquals("FAILED UserServiceTest > find", ci.failedJobLogs.single().tail)

        val discussion = collected.metadata.discussions.single()
        assertEquals(false, discussion.resolved)
        assertEquals(servicePath to 4, discussion.path to discussion.line)
    }

    @Test
    fun rendersWellFormedRedactedContext() {
        val redactor = SecretRedactor(listOf(token))
        val collected = collectWith(session(fakeGitLab()), 7, testOptions(), redactor)
        val rendered = ReviewContextRenderer(testOptions().chunkBudget, redactor).render(collected)

        (listOf(rendered.meta, rendered.project) + rendered.chunks.map { it.xml }).forEach { xml ->
            XmlTextTest.parse(xml)
            assertFalse(xml.contains(token))
        }
        assertEquals("mr_7_meta.xml", rendered.names.meta)
        assertEquals(listOf("mr_7_chunk_1.xml"), rendered.chunks.map { it.fileName })

        val meta = rendered.meta
        assertTrue(meta.contains("""kind="merge_request""""))
        assertTrue(meta.contains("<reference>!7</reference>"))
        assertTrue(meta.contains("Should this handle null ids?"))
        assertFalse(meta.contains("added 1 commit"))
        assertTrue(meta.contains("""resolved="false" file="$servicePath" line="4""""))
        assertTrue(meta.contains("""<ci id="99" status="failed""""))
        assertTrue(meta.contains("""reason="sensitive""""))
        assertTrue(redactor.redactionCount >= 3)
        assertTrue(rendered.project.contains("Wires the repository"))
    }

    @Test
    fun splitsLargeFilesAcrossChunksWithinBudget() {
        val bigService = (1..900).joinToString("\n", postfix = "\n") { "    val field$it = compute($it) + other($it)" }
        val budget = ChunkBudget(tokens = 3_000, lines = 300)
        val redactor = SecretRedactor(listOf(token))
        val collected = collectWith(session(fakeGitLab(serviceAtHead = bigService)), 7, testOptions(budget = budget), redactor)
        val rendered = ReviewContextRenderer(budget, redactor).render(collected)

        assertTrue(rendered.chunks.size > 3)
        rendered.chunks.forEach { chunk ->
            XmlTextTest.parse(chunk.xml)
            assertTrue(TokenEstimator.estimate(chunk.xml) <= budget.tokens, "chunk ${chunk.index} is over the token budget")
            assertTrue(TokenEstimator.lineCount(chunk.xml) <= budget.lines, "chunk ${chunk.index} is over the line budget")
        }
        val reassembled = rendered.chunks.joinToString("") { it.xml }
        assertTrue(reassembled.contains("parts="))
        assertTrue(reassembled.contains("field900"))
    }

    @Test
    fun buildsWideContextDiffWithTheLineDiffEngine() {
        val collected = collectWith(session(fakeGitLab()), 7, testOptions(contextLines = 20), SecretRedactor(listOf(token)))
        val service = collected.sections.single { it.file.newPath == servicePath }
        assertEquals(DiffSource.IDE, service.diffSource)
        assertTrue(service.diff.orEmpty().contains("\n package app\n"))
        assertEquals(2, service.stats.additions)
    }

    @Test
    fun failsClearlyWhenGitLabHasNoDiffYet() {
        val transport = FakeApiTransport().apply {
            json("projects/group%2Fapp", """{"id": 1, "path_with_namespace": "group/app"}""")
            json("projects/1/merge_requests/8", """{"iid": 8, "title": "Empty", "diff_refs": null}""")
        }
        val session = session(transport)
        assertFailsWith<ReviewContextException> { runBlocking { session.request(8) } }
    }

    @Test
    fun closesTransportWhenProjectCannotBeOpened() {
        val transport = FakeApiTransport()
        assertFailsWith<Exception> { session(transport) }
        assertTrue(transport.closed)
    }
}
