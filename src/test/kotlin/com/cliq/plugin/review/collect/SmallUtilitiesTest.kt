package com.cliq.plugin.review.collect

import com.cliq.plugin.review.collect.source.ContentDecoder
import com.cliq.plugin.review.collect.source.FileContent
import com.cliq.plugin.review.git.GitRefNames
import com.cliq.plugin.review.settings.CliqReviewSettings
import com.cliq.plugin.review.ui.ReviewPrompt
import com.cliq.plugin.terminal.TerminalTyper
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmallUtilitiesTest {

    @Test
    fun cleansGitLabJobLogs() {
        val raw = "section_start:1700000000:build_script\r\u001B[0K\u001B[32;1m$ ./gradlew test\u001B[0;m\r\n" +
            "progress 10%\rprogress 100%\n" +
            "\u001B[31;1mERROR: Job failed\u001B[0m\n" +
            "section_end:1700000001:build_script\r\u001B[0K\n"
        assertEquals("$ ./gradlew test\nprogress 100%\nERROR: Job failed", JobLogCleaner.clean(raw, 10))
        assertEquals("ERROR: Job failed", JobLogCleaner.clean(raw, 1))
    }

    @Test
    fun stripsGitHubActionsTimestamps() {
        val raw = "\uFEFF2024-01-01T10:00:00.1234567Z ##[group]Run tests\r\n2024-01-01T10:00:01.0000000Z FAILED x\n"
        assertEquals("##[group]Run tests\nFAILED x", JobLogCleaner.clean(raw, 10))
    }

    @Test
    fun rendersPromptAndNeutralisesHostileTitles() {
        val prompt = ReviewPrompt.render(
            "Review {{kind}} {{reference}} {{ title }} {{unknown}} in {{contextDir}} ({{chunkCount}}, {{metaFile}})",
            ReviewPrompt.Values(
                kind = "pull request",
                reference = "#42",
                number = 42,
                title = "Fix\u001B[201~\nrm -rf /\u0007 now",
                webUrl = "https://github.com/o/r/pull/42",
                sourceBranch = "feature",
                targetBranch = "main",
                chunkCount = 3,
                contextDirectory = ".cliq/reviews/pr-42",
                metaFile = "pr_42_meta.xml",
                projectFile = "pr_42_project.xml",
                reportFile = "CODE_REVIEW_PR42.md",
            ),
        )
        assertEquals("Review pull request #42 Fix [201~ rm -rf / now {{unknown}} in .cliq/reviews/pr-42 (3, pr_42_meta.xml)", prompt)
        assertFalse(prompt.any { it == '\u001B' })
    }

    @Test
    fun defaultPromptTemplateUsesOnlyKnownPlaceholders() {
        val rendered = ReviewPrompt.render(
            CliqReviewSettings.DEFAULT_REVIEW_PROMPT_TEMPLATE,
            ReviewPrompt.Values("merge request", "!1", 1, "t", "u", "s", "t", 2, "d", "m", "p", "r"),
        )
        assertFalse(rendered.contains("{{"))
    }

    @Test
    fun terminalPayloadCannotEscapeBracketedPaste() {
        val sanitized = TerminalTyper.stripControlSequences("ok\u001B[201~\r\u009Bx\n\tend")
        assertEquals("ok[201~x\n\tend", sanitized)
    }

    @Test
    fun validatesGitReferences() {
        assertTrue(GitRefNames.isSha("a".repeat(40)))
        assertFalse(GitRefNames.isSha("HEAD"))
        assertFalse(GitRefNames.isSha("--upload-pack=x"))
        assertTrue(GitRefNames.isSafeBranch("feature/JIRA-12_fix"))
        listOf("-x", "a..b", "a b", "a~1", "a:b", "x.lock", "a/", "a@{1}", "")
            .forEach { assertFalse(GitRefNames.isSafeBranch(it), it) }
    }

    @Test
    fun decodesContentAndDetectsBinary() {
        assertIs<FileContent.Binary>(ContentDecoder.decode(byteArrayOf(1, 0, 2), 100))
        assertIs<FileContent.TooLarge>(ContentDecoder.decode(ByteArray(11), 10))
        val text = ContentDecoder.decode("﻿hello".toByteArray(), 100)
        assertIs<FileContent.Text>(text)
        assertEquals("hello", text.value)
    }
}
