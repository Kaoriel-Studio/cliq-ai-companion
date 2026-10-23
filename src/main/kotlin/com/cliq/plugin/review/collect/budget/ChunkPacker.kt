package com.cliq.plugin.review.collect.budget

data class ChunkBudget(val tokens: Int, val lines: Int) {

    fun reserve(tokens: Int, lines: Int): ChunkBudget =
        ChunkBudget((this.tokens - tokens).coerceAtLeast(MIN_TOKENS), (this.lines - lines).coerceAtLeast(MIN_LINES))

    fun fits(tokens: Int, lines: Int): Boolean = tokens <= this.tokens && lines <= this.lines

    private companion object {
        const val MIN_TOKENS = 200
        const val MIN_LINES = 20
    }
}

class ContextUnit(val xml: String) {
    val tokens: Int = TokenEstimator.estimate(xml)
    val lines: Int = TokenEstimator.lineCount(xml)
}

class PackedChunk(val units: List<ContextUnit>) {
    val tokens: Int get() = units.sumOf { it.tokens }
    val lines: Int get() = units.sumOf { it.lines }
}

object ChunkPacker {

    fun pack(units: List<ContextUnit>, budget: ChunkBudget): List<PackedChunk> {
        val chunks = mutableListOf<PackedChunk>()
        var current = mutableListOf<ContextUnit>()
        var tokens = 0
        var lines = 0

        units.forEach { unit ->
            val overflows = current.isNotEmpty() && !budget.fits(tokens + unit.tokens, lines + unit.lines)
            if (overflows) {
                chunks += PackedChunk(current)
                current = mutableListOf()
                tokens = 0
                lines = 0
            }
            current += unit
            tokens += unit.tokens
            lines += unit.lines
        }
        if (current.isNotEmpty()) chunks += PackedChunk(current)
        return chunks
    }
}

object PayloadSplitter {

    private const val WRAP_CHARS_PERCENT_OF_TOKENS = 90

    fun split(payload: String, budget: ChunkBudget): List<String> {
        if (budget.fits(TokenEstimator.estimate(payload), TokenEstimator.lineCount(payload))) return listOf(payload)

        val slices = mutableListOf<String>()
        val current = StringBuilder()
        var rawTokens = 0
        var lines = 0

        fun flush() {
            if (current.isEmpty()) return
            slices += current.toString()
            current.setLength(0)
            rawTokens = 0
            lines = 0
        }

        val sourceLines = payload.split('\n')
        sourceLines.forEachIndexed { lineIndex, line ->
            val pieces = hardWrap(line, budget.tokens)
            pieces.forEachIndexed { pieceIndex, piece ->
                val terminator = if (pieceIndex == pieces.lastIndex && lineIndex != sourceLines.lastIndex) "\n" else ""
                val pieceTokens = TokenEstimator.estimateRaw(piece) + terminator.length
                if (current.isNotEmpty() && !budget.fits(TokenEstimator.withMargin(rawTokens + pieceTokens), lines + 1)) flush()
                current.append(piece).append(terminator)
                rawTokens += pieceTokens
                lines++
            }
        }
        flush()
        return slices
    }

    private fun hardWrap(line: String, maxTokens: Int): List<String> {
        if (TokenEstimator.estimate(line) <= maxTokens) return listOf(line)
        val maxChars = (maxTokens * WRAP_CHARS_PERCENT_OF_TOKENS / 100).coerceAtLeast(1)
        return line.chunked(maxChars)
    }
}
