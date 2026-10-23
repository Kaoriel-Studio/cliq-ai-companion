package com.cliq.plugin.review.collect.diff

import org.junit.Test
import kotlin.test.assertEquals

class UnifiedDiffFormatterTest {

    private val before = (1..20).joinToString("\n") { "line $it" }

    @Test
    fun formatsSingleHunkWithRequestedContext() {
        val after = before.replace("line 10", "line ten")
        val diff = UnifiedDiffFormatter.format("a.txt", "a.txt", before, after, listOf(LineChange(9, 10, 9, 10)), 2)
        val expected = """
            --- a/a.txt
            +++ b/a.txt
            @@ -8,5 +8,5 @@
             line 8
             line 9
            -line 10
            +line ten
             line 11
             line 12
        """.trimIndent()
        assertEquals(expected, diff)
    }

    @Test
    fun mergesNearbyChangesAndSplitsDistantOnes() {
        val after = before.replace("line 3", "LINE 3").replace("line 6", "LINE 6").replace("line 18", "LINE 18")
        val changes = listOf(LineChange(2, 3, 2, 3), LineChange(5, 6, 5, 6), LineChange(17, 18, 17, 18))
        val diff = UnifiedDiffFormatter.format("a", "a", before, after, changes, 2)
        assertEquals(2, diff.lines().count { it.startsWith("@@") })
        assertEquals(DiffStats(additions = 3, deletions = 3), DiffStats.of(diff))
    }

    @Test
    fun formatsPureInsertionHeaders() {
        val after = before.replace("line 1\n", "line 1\ninserted\n")
        val diff = UnifiedDiffFormatter.format("a", "a", before, after, listOf(LineChange(1, 1, 1, 2)), 0)
        assertEquals("@@ -1,0 +2,1 @@", diff.lines()[2])
    }

    @Test
    fun returnsEmptyDiffWithoutChanges() {
        assertEquals("", UnifiedDiffFormatter.format("a", "a", before, before, emptyList(), 3))
    }

    @Test
    fun countsOnlyHunkLinesInStats() {
        val diff = "--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n--- removed dashes\n+++ added pluses\n context"
        assertEquals(DiffStats(additions = 1, deletions = 1), DiffStats.of(diff))
    }
}
