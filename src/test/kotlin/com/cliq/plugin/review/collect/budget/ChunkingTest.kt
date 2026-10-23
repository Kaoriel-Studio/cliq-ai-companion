package com.cliq.plugin.review.collect.budget

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkingTest {

    @Test
    fun estimatesCodeDenserThanPlainBytesOverFour() {
        val code = "if (a[i] != b[j]) { x += y * (z - 1); }\n".repeat(50)
        assertTrue(TokenEstimator.estimate(code) > code.length / 4)
    }

    @Test
    fun estimatesCyrillicAndCjkHigherThanAscii() {
        val ascii = "hello world ".repeat(10)
        val cyrillic = "привет мир ".repeat(10)
        val cjk = "你好世界".repeat(10)
        assertTrue(TokenEstimator.estimate(cyrillic) > TokenEstimator.estimate(ascii))
        assertTrue(TokenEstimator.estimate(cjk) >= 40)
    }

    @Test
    fun countsLines() {
        assertEquals(0, TokenEstimator.lineCount(""))
        assertEquals(1, TokenEstimator.lineCount("a"))
        assertEquals(2, TokenEstimator.lineCount("a\nb\n"))
        assertEquals(3, TokenEstimator.lineCount("a\n\nb"))
    }

    @Test
    fun packsUnitsInOrderWithinBudget() {
        val units = (1..10).map { ContextUnit("<u>${"word ".repeat(40)}</u>\n") }
        val budget = ChunkBudget(tokens = units.first().tokens * 3, lines = 1_000)
        val chunks = ChunkPacker.pack(units, budget)
        assertEquals(4, chunks.size)
        assertEquals(units, chunks.flatMap { it.units })
        chunks.forEach { assertTrue(it.tokens <= budget.tokens) }
    }

    @Test
    fun respectsLineBudget() {
        val units = (1..6).map { ContextUnit("a\nb\nc\nd\n") }
        val chunks = ChunkPacker.pack(units, ChunkBudget(tokens = 100_000, lines = 8))
        assertEquals(3, chunks.size)
        chunks.forEach { assertTrue(it.lines <= 8) }
    }

    @Test
    fun keepsOversizedUnitInItsOwnChunk() {
        val small = ContextUnit("<a/>\n")
        val huge = ContextUnit("x ".repeat(10_000))
        val chunks = ChunkPacker.pack(listOf(small, huge, small), ChunkBudget(tokens = 500, lines = 100))
        assertEquals(3, chunks.size)
    }

    @Test
    fun splitsPayloadByLinesWithinBudget() {
        val payload = (1..500).joinToString("\n") { "line number $it with some content" }
        val budget = ChunkBudget(tokens = 1_000, lines = 120)
        val slices = PayloadSplitter.split(payload, budget)
        assertTrue(slices.size > 1)
        assertEquals(payload, slices.joinToString(""))
        slices.forEach { slice ->
            assertTrue(TokenEstimator.estimate(slice) <= budget.tokens + 5)
            assertTrue(TokenEstimator.lineCount(slice) <= budget.lines)
        }
    }

    @Test
    fun hardWrapsMinifiedLines() {
        val payload = "x".repeat(50_000)
        val budget = ChunkBudget(tokens = 1_000, lines = 50)
        val slices = PayloadSplitter.split(payload, budget)
        assertTrue(slices.size > 1)
        assertEquals(payload, slices.joinToString(""))
        slices.forEach { assertTrue(TokenEstimator.estimate(it) <= budget.tokens + 5) }
    }

    @Test
    fun returnsSmallPayloadUnchanged() {
        assertEquals(listOf("tiny"), PayloadSplitter.split("tiny", ChunkBudget(1_000, 100)))
    }
}
