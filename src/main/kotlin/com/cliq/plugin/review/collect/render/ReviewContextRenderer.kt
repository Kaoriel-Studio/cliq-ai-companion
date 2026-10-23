package com.cliq.plugin.review.collect.render

import com.cliq.plugin.review.collect.budget.ChunkBudget
import com.cliq.plugin.review.collect.budget.ChunkPacker
import com.cliq.plugin.review.collect.budget.PackedChunk
import com.cliq.plugin.review.collect.filter.SecretRedactor
import com.cliq.plugin.review.model.CiReport
import com.cliq.plugin.review.model.CollectedReview
import com.cliq.plugin.review.model.ProjectContext
import com.cliq.plugin.review.model.ReviewDiscussion
import com.cliq.plugin.review.provider.ProviderKind
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

class ContextFileNames(val prefix: String, val number: Long) {

    constructor(kind: ProviderKind, number: Long) : this(kind.filePrefix, number)

    val meta: String get() = "${prefix}_${number}_meta.xml"
    val project: String get() = "${prefix}_${number}_project.xml"
    fun chunk(index: Int): String = "${prefix}_${number}_chunk_$index.xml"
}

class RenderedChunk(val index: Int, val fileName: String, val xml: String, val tokens: Int)

class RenderedContext(
    val names: ContextFileNames,
    val meta: String,
    val project: String,
    val chunks: List<RenderedChunk>,
) {
    val chunkTokens: Int get() = chunks.sumOf { it.tokens }
}

class ReviewContextRenderer(
    private val budget: ChunkBudget,
    private val redactor: SecretRedactor,
    private val clock: Clock = Clock.systemUTC(),
) {

    private companion object {
        const val GENERATOR = "Cliq AI Companion"
        const val CHUNK_ENVELOPE_TOKENS = 300
        const val CHUNK_ENVELOPE_LINES = 8
        const val MAX_NOTE_LENGTH = 8_000
        const val MAX_NOTES = 400
        const val MAX_COMMIT_MESSAGE_LENGTH = 4_000
        const val TRUNCATION_MARKER = "\n[truncated by Cliq]"
        const val CHUNK_HINT =
            "Each file element holds the diff (with wide context when available), content_after (the complete file " +
                "at the head commit) and, for heavily rewritten files, content_before. related_test and related_file " +
                "elements hold unchanged tests and dependent or neighbouring files at the head commit; the for attribute " +
                "names the changed file they relate to. Elements with part and parts attributes are split across chunks. " +
                "Text marked [REDACTED:kind] was removed by Cliq because it looked like a secret."
    }

    private class MetaTexts(
        val title: String,
        val description: String,
        val discussions: String,
        val warnings: List<String>,
    )

    fun render(collected: CollectedReview): RenderedContext {
        val metadata = collected.metadata
        val names = ContextFileNames(metadata.kind, metadata.request.number)
        val unitBudget = budget.reserve(CHUNK_ENVELOPE_TOKENS, CHUNK_ENVELOPE_LINES)
        val factory = ContextUnitFactory(unitBudget, collected.diffContextLines)
        val units = collected.sections.flatMap { section ->
            factory.fileUnits(section) + collected.related[section.file.newPath].orEmpty().flatMap(factory::relatedUnits)
        }
        val packed = ChunkPacker.pack(units, unitBudget)
        val chunks = packed.mapIndexed { index, chunk -> renderChunk(names, index + 1, packed.size, chunk) }
        val project = renderProject(collected)
        val texts = MetaTexts(
            title = redactor.redact(metadata.request.title),
            description = redactor.redact(metadata.request.description),
            discussions = XmlBuilder(1).apply { renderDiscussions(metadata.discussions) }.build(),
            warnings = collected.warnings.map(redactor::redact),
        )
        return RenderedContext(names, renderMeta(collected, names, chunks, texts), project, chunks)
    }

    private fun renderChunk(names: ContextFileNames, index: Int, total: Int, chunk: PackedChunk): RenderedChunk {
        val xml = XmlBuilder()
            .declaration()
            .element("review_chunk", "index" to index, "total" to total, "number" to names.number) {
                text("hint", CHUNK_HINT)
                chunk.units.forEach { raw(it.xml) }
            }
            .build()
        return RenderedChunk(index, names.chunk(index), xml, chunk.tokens)
    }

    private fun renderMeta(
        collected: CollectedReview,
        names: ContextFileNames,
        chunks: List<RenderedChunk>,
        texts: MetaTexts,
    ): String {
        val metadata = collected.metadata
        val request = metadata.request
        return XmlBuilder()
            .declaration()
            .element("review_context", "generator" to GENERATOR, "kind" to metadata.kind.requestNoun.replace(' ', '_')) {
                element("metadata") {
                    text("provider", metadata.kind.displayName)
                    text("number", request.number)
                    text("reference", metadata.kind.reference(request.number))
                    text("project", metadata.project.path)
                    text("title", texts.title)
                    text("author", request.author)
                    text("state", request.state)
                    text("draft", request.draft)
                    text("source_branch", request.sourceBranch)
                    text("target_branch", request.targetBranch)
                    text("labels", request.labels.joinToString(", "))
                    text("web_url", request.webUrl)
                    text("base_sha", request.refs.baseSha)
                    request.refs.startSha?.let { text("start_sha", it) }
                    text("head_sha", request.refs.headSha)
                    text("generated_at", Instant.now(clock).truncatedTo(ChronoUnit.SECONDS))
                    text("total_files", metadata.changes.size)
                }
                element("reading_order") {
                    empty("file", "name" to names.meta, "role" to "meta")
                    empty("file", "name" to names.project, "role" to "project")
                    chunks.forEach { empty("file", "name" to it.fileName, "role" to "chunk", "approx_tokens" to it.tokens) }
                }
                element("collection") {
                    text("diff_context_lines", collected.diffContextLines)
                    text("local_git", collected.usedLocalGit)
                    text("redactions", redactor.redactionCount)
                    val skipped = collected.sections.filter { it.skipReason != null }
                    if (skipped.isNotEmpty()) {
                        element("skipped_files") {
                            skipped.forEach { empty("file", "path" to it.file.newPath, "reason" to it.skipReason) }
                        }
                    }
                    if (texts.warnings.isNotEmpty()) {
                        element("warnings") { texts.warnings.forEach { text("warning", it) } }
                    }
                }
                cdata("description", texts.description)
                renderCi(metadata.ci)
                raw(texts.discussions)
                element("changed_files") {
                    collected.sections.forEach { section ->
                        val file = section.file
                        empty(
                            "file",
                            "path" to file.newPath,
                            "status" to file.status.xmlValue,
                            "old_path" to file.oldPath.takeIf { it != file.newPath },
                            "additions" to section.stats.additions.takeIf { section.diff != null },
                            "deletions" to section.stats.deletions.takeIf { section.diff != null },
                            "skipped" to section.skipReason,
                        )
                    }
                }
            }
            .build()
    }

    private fun XmlBuilder.renderCi(report: CiReport?) {
        if (report == null) {
            element("ci") { empty("none") }
            return
        }
        element("ci", "id" to report.id, "status" to report.status, "ref" to report.ref, "url" to report.url) {
            element("jobs") {
                report.jobs.forEach { job ->
                    empty(
                        "job",
                        "name" to job.name,
                        "stage" to job.stage,
                        "status" to job.status,
                        "allow_failure" to job.allowFailure.takeIf { it },
                    )
                }
            }
            if (report.failedJobLogs.isNotEmpty()) {
                element("failed_job_logs") {
                    report.failedJobLogs.forEach { log ->
                        cdata("log", log.tail, "job" to log.job.name, "stage" to log.job.stage, "lines" to "tail")
                    }
                }
            }
        }
    }

    private fun XmlBuilder.renderDiscussions(discussions: List<ReviewDiscussion>) {
        var remaining = MAX_NOTES
        element("discussions") {
            for (discussion in discussions) {
                if (remaining <= 0) break
                val notes = discussion.notes.take(remaining)
                if (notes.isEmpty()) continue
                remaining -= notes.size
                element(
                    "discussion",
                    "resolved" to discussion.resolved,
                    "file" to discussion.path,
                    "line" to discussion.line,
                ) {
                    notes.forEach { note ->
                        cdata(
                            "note",
                            redactor.redact(truncate(note.body, MAX_NOTE_LENGTH)),
                            "author" to note.author,
                            "created" to note.createdAt,
                        )
                    }
                }
            }
        }
    }

    private fun renderProject(collected: CollectedReview): String {
        val metadata = collected.metadata
        return XmlBuilder()
            .declaration()
            .element("project_context", "project" to metadata.project.path, "head_sha" to metadata.request.refs.headSha) {
                renderConfigs(collected.projectContext)
                element("commits") {
                    metadata.commits.forEach { commit ->
                        cdata(
                            "commit",
                            redactor.redact(truncate(commit.message, MAX_COMMIT_MESSAGE_LENGTH)),
                            "sha" to commit.shortSha,
                            "author" to commit.author,
                            "date" to commit.date,
                        )
                    }
                }
                element("touched_dirs") {
                    collected.projectContext.directories.forEach { directory ->
                        element("dir", "path" to directory.path.ifEmpty { "." }, "truncated" to directory.truncated.takeIf { it }) {
                            directory.entries.forEach { entry ->
                                text("entry", entry.name, "type" to if (entry.isDirectory) "tree" else "blob")
                            }
                        }
                    }
                }
            }
            .build()
    }

    private fun XmlBuilder.renderConfigs(context: ProjectContext) {
        element("configs") {
            context.configs.forEach { config ->
                if (config.content == null) {
                    empty("config", "path" to config.path, "omitted" to config.omittedReason)
                } else {
                    cdata("config", config.content, "path" to config.path)
                }
            }
        }
    }

    private fun truncate(text: String, maxLength: Int): String =
        if (text.length <= maxLength) text else text.take(maxLength) + TRUNCATION_MARKER
}
