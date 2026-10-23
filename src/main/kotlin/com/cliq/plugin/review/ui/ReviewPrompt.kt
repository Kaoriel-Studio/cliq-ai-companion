package com.cliq.plugin.review.ui

import com.cliq.plugin.review.settings.CliqReviewSettings

object ReviewPrompt {

    private const val MAX_VALUE_LENGTH = 300
    private val PLACEHOLDER = Regex("\\{\\{\\s*(\\w+)\\s*}}")
    private val WHITESPACE = Regex("\\s+")

    data class Values(
        val kind: String,
        val reference: String,
        val number: Long,
        val title: String,
        val webUrl: String,
        val sourceBranch: String,
        val targetBranch: String,
        val chunkCount: Int,
        val contextDirectory: String,
        val metaFile: String,
        val projectFile: String,
        val reportFile: String,
    )

    fun render(template: String, values: Values): String {
        val substitutions = mapOf(
            CliqReviewSettings.PLACEHOLDER_KIND to values.kind,
            CliqReviewSettings.PLACEHOLDER_REFERENCE to values.reference,
            CliqReviewSettings.PLACEHOLDER_NUMBER to values.number.toString(),
            CliqReviewSettings.PLACEHOLDER_TITLE to values.title,
            CliqReviewSettings.PLACEHOLDER_URL to values.webUrl,
            CliqReviewSettings.PLACEHOLDER_SOURCE_BRANCH to values.sourceBranch,
            CliqReviewSettings.PLACEHOLDER_TARGET_BRANCH to values.targetBranch,
            CliqReviewSettings.PLACEHOLDER_CHUNK_COUNT to values.chunkCount.toString(),
            CliqReviewSettings.PLACEHOLDER_CONTEXT_DIR to values.contextDirectory,
            CliqReviewSettings.PLACEHOLDER_META_FILE to values.metaFile,
            CliqReviewSettings.PLACEHOLDER_PROJECT_FILE to values.projectFile,
            CliqReviewSettings.PLACEHOLDER_REPORT_FILE to values.reportFile,
        ).mapValues { (_, value) -> singleLine(value) }

        val rendered = PLACEHOLDER.replace(template) { match ->
            substitutions[match.groupValues[1]] ?: match.value
        }
        return stripControlCharacters(rendered).trim()
    }

    fun singleLine(value: String): String =
        value.map { if (it.isISOControl() || it == ' ' || it == ' ') ' ' else it }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_VALUE_LENGTH)

    private fun stripControlCharacters(text: String): String =
        text.filter { !it.isISOControl() || it == '\n' || it == '\t' }
}
