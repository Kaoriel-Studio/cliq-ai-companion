package com.cliq.plugin.review.collect.diff

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.openapi.progress.EmptyProgressIndicator

data class LineChange(
    val beforeStart: Int,
    val beforeEnd: Int,
    val afterStart: Int,
    val afterEnd: Int,
)

fun interface LineDiffEngine {
    fun compare(before: String, after: String): List<LineChange>?
}

object IdeLineDiffEngine : LineDiffEngine {

    override fun compare(before: String, after: String): List<LineChange>? =
        try {
            ComparisonManager.getInstance()
                .compareLines(before, after, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
                .map { LineChange(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
        } catch (e: DiffTooBigException) {
            null
        }
}

data class DiffStats(val additions: Int, val deletions: Int) {

    val changedLines: Int get() = additions + deletions

    companion object {
        val EMPTY = DiffStats(0, 0)

        fun of(diff: String): DiffStats {
            var additions = 0
            var deletions = 0
            var inHunk = false
            diff.lineSequence().forEach { line ->
                when {
                    line.startsWith("@@") -> inHunk = true
                    !inHunk -> Unit
                    line.startsWith('+') -> additions++
                    line.startsWith('-') -> deletions++
                }
            }
            return DiffStats(additions, deletions)
        }
    }
}

object UnifiedDiffFormatter {

    fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n")

    fun format(
        oldPath: String,
        newPath: String,
        before: String,
        after: String,
        changes: List<LineChange>,
        contextLines: Int,
    ): String {
        if (changes.isEmpty()) return ""
        val beforeLines = before.split('\n')
        val afterLines = after.split('\n')
        val out = StringBuilder()
        out.append("--- a/").append(oldPath).append('\n')
        out.append("+++ b/").append(newPath).append('\n')
        groupIntoHunks(changes, contextLines).forEach { hunk ->
            appendHunk(out, hunk, beforeLines, afterLines, contextLines)
        }
        return out.toString().trimEnd('\n')
    }

    private fun groupIntoHunks(changes: List<LineChange>, contextLines: Int): List<List<LineChange>> {
        val hunks = mutableListOf<MutableList<LineChange>>()
        changes.sortedBy { it.beforeStart }.forEach { change ->
            val previous = hunks.lastOrNull()?.last()
            if (previous != null && change.beforeStart - previous.beforeEnd <= 2 * contextLines) {
                hunks.last().add(change)
            } else {
                hunks.add(mutableListOf(change))
            }
        }
        return hunks
    }

    private fun appendHunk(
        out: StringBuilder,
        hunk: List<LineChange>,
        beforeLines: List<String>,
        afterLines: List<String>,
        contextLines: Int,
    ) {
        val first = hunk.first()
        val last = hunk.last()
        val beforeFrom = (first.beforeStart - contextLines).coerceAtLeast(0)
        val beforeTo = (last.beforeEnd + contextLines).coerceAtMost(beforeLines.size)
        val afterFrom = (first.afterStart - (first.beforeStart - beforeFrom)).coerceAtLeast(0)
        val afterTo = (last.afterEnd + (beforeTo - last.beforeEnd)).coerceAtMost(afterLines.size)

        out.append("@@ -").append(rangeHeader(beforeFrom, beforeTo - beforeFrom))
            .append(" +").append(rangeHeader(afterFrom, afterTo - afterFrom))
            .append(" @@\n")

        var cursor = beforeFrom
        hunk.forEach { change ->
            for (index in cursor until change.beforeStart) appendLine(out, ' ', beforeLines[index])
            for (index in change.beforeStart until change.beforeEnd) appendLine(out, '-', beforeLines[index])
            for (index in change.afterStart until change.afterEnd) appendLine(out, '+', afterLines[index])
            cursor = change.beforeEnd
        }
        for (index in cursor until beforeTo) appendLine(out, ' ', beforeLines[index])
    }

    private fun rangeHeader(from: Int, count: Int): String {
        val start = if (count == 0) from else from + 1
        return "$start,$count"
    }

    private fun appendLine(out: StringBuilder, marker: Char, line: String) {
        out.append(marker).append(line).append('\n')
    }
}
