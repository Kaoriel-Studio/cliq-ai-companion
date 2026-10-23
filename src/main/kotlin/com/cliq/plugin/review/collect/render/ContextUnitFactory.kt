package com.cliq.plugin.review.collect.render

import com.cliq.plugin.review.collect.budget.ChunkBudget
import com.cliq.plugin.review.collect.budget.ContextUnit
import com.cliq.plugin.review.collect.budget.PayloadSplitter
import com.cliq.plugin.review.model.DiffSource
import com.cliq.plugin.review.model.FileSection
import com.cliq.plugin.review.model.RelatedFile

class ContextUnitFactory(
    private val unitBudget: ChunkBudget,
    private val diffContextLines: Int,
) {

    private companion object {
        const val ELEMENT_OVERHEAD_TOKENS = 120
        const val ELEMENT_OVERHEAD_LINES = 8
        const val API_DIFF_CONTEXT_LINES = 3
        const val BASE_DEPTH = 1
    }

    private class Payload(
        val element: String,
        val attributes: List<Pair<String, Any?>>,
        val text: String,
    )

    private val payloadBudget = unitBudget.reserve(ELEMENT_OVERHEAD_TOKENS, ELEMENT_OVERHEAD_LINES)

    fun fileUnits(section: FileSection): List<ContextUnit> {
        val payloads = payloadsOf(section)
        val whole = ContextUnit(renderFile(section, payloads, part = null))
        if (unitBudget.fits(whole.tokens, whole.lines)) return listOf(whole)

        val slices = payloads.flatMap { payload ->
            PayloadSplitter.split(payload.text, payloadBudget).map { Payload(payload.element, payload.attributes, it) }
        }
        return slices.mapIndexed { index, slice ->
            ContextUnit(renderFile(section, listOf(slice), part = index + 1 to slices.size))
        }
    }

    fun relatedUnits(related: RelatedFile): List<ContextUnit> {
        val whole = ContextUnit(renderRelated(related, related.content, part = null))
        if (unitBudget.fits(whole.tokens, whole.lines)) return listOf(whole)
        val slices = PayloadSplitter.split(related.content, payloadBudget)
        return slices.mapIndexed { index, slice ->
            ContextUnit(renderRelated(related, slice, part = index + 1 to slices.size))
        }
    }

    private fun payloadsOf(section: FileSection): List<Payload> = buildList {
        section.diff?.let { diff ->
            val contextLines = if (section.diffSource == DiffSource.API) API_DIFF_CONTEXT_LINES else diffContextLines
            add(Payload("diff", listOf("source" to section.diffSource?.xmlValue, "context_lines" to contextLines), diff))
        }
        section.contentAfter?.let { add(Payload("content_after", emptyList(), it)) }
        section.contentBefore?.let { add(Payload("content_before", emptyList(), it)) }
    }

    private fun renderFile(section: FileSection, payloads: List<Payload>, part: Pair<Int, Int>?): String {
        val file = section.file
        val attributes = arrayOf(
            "path" to file.newPath,
            "status" to file.status.xmlValue,
            "old_path" to file.oldPath.takeIf { it != file.newPath },
            "additions" to section.stats.additions.takeIf { section.diff != null },
            "deletions" to section.stats.deletions.takeIf { section.diff != null },
            "skipped" to section.skipReason,
            "part" to part?.first,
            "parts" to part?.second,
        )
        val builder = XmlBuilder(BASE_DEPTH)
        if (section.skipReason != null) return builder.empty("file", *attributes).build()
        return builder.element("file", *attributes) {
            payloads.forEach { payload -> cdata(payload.element, payload.text, *payload.attributes.toTypedArray()) }
            if (part == null || part.first == 1) section.notes.forEach { text("note", it) }
        }.build()
    }

    private fun renderRelated(related: RelatedFile, content: String, part: Pair<Int, Int>?): String =
        XmlBuilder(BASE_DEPTH).cdata(
            related.kind.elementName,
            content,
            "path" to related.path,
            "for" to related.ownerPath,
            "reason" to related.kind.reason,
            "part" to part?.first,
            "parts" to part?.second,
        ).build()
}
