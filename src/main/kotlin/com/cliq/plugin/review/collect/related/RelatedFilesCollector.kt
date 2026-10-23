package com.cliq.plugin.review.collect.related

import com.cliq.plugin.review.collect.filter.PathFilter
import com.cliq.plugin.review.collect.filter.SecretRedactor
import com.cliq.plugin.review.model.ChangeStatus
import com.cliq.plugin.review.model.ChangedFile
import com.cliq.plugin.review.model.RelatedFile
import com.cliq.plugin.review.model.RelatedKind
import com.cliq.plugin.review.collect.source.DirectoryListings
import com.cliq.plugin.review.collect.source.FileContent
import com.cliq.plugin.review.collect.source.RepositoryReader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class RelatedFilesCollector(
    private val reader: RepositoryReader,
    private val listings: DirectoryListings,
    private val index: RelatedFileIndex,
    private val headSha: String,
    private val pathFilter: PathFilter,
    private val includeTests: Boolean,
    private val maxRelatedPerChange: Int,
    maxFileBytes: Int,
    private val redactor: SecretRedactor,
) {

    companion object {
        const val CONTEXT_DIRECTORY_PREFIX = ".cliq/"
        private const val MAX_TESTS_PER_CHANGE = 2
        private const val MAX_RELATED_TOTAL = 80
        private const val MAX_RELATED_BYTES = 64 * 1024
        private const val DEPENDENT_SEARCH_LIMIT = 40
        private const val MIN_SEARCH_WORD_LENGTH = 4
        private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val GENERIC_STEMS = setOf(
            "index", "main", "init", "__init__", "mod", "lib", "utils", "util", "helpers", "helper", "types",
            "constants", "config", "settings", "common", "core", "base", "module", "package", "setup", "models",
            "model", "views", "view", "routes", "router", "schema", "server", "client", "service", "services",
            "test", "tests", "spec", "styles", "style", "app", "application", "default", "defaults", "data",
        )
        private val SOURCE_EXTENSIONS = setOf(
            "kt", "kts", "java", "groovy", "scala", "go", "py", "rb", "rs", "php", "cs", "swift", "dart", "ex", "exs",
            "ts", "tsx", "js", "jsx", "mjs", "cjs", "mts", "cts", "vue", "svelte",
            "c", "cc", "cpp", "cxx", "h", "hpp", "m", "mm", "lua", "clj", "erl", "hs", "ml", "fs", "sql",
        )

        fun isSourceFile(path: String): Boolean =
            path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in SOURCE_EXTENSIONS

        fun searchableWord(path: String): String? {
            val stem = path.substringAfterLast('/').substringBeforeLast('.')
            if (stem.length < MIN_SEARCH_WORD_LENGTH || !IDENTIFIER.matches(stem)) return null
            return stem.takeIf { it.lowercase() !in GENERIC_STEMS }
        }

        fun proximity(first: String, second: String): Int {
            val a = first.split('/').dropLast(1)
            val b = second.split('/').dropLast(1)
            return a.zip(b).takeWhile { (left, right) -> left == right }.size
        }
    }

    private class Candidates(
        val owner: String,
        val tests: List<String>,
        val dependents: List<String>,
        val siblings: List<String>,
    )

    private class Planned(val path: String, val owner: String, val kind: RelatedKind)

    private val maxBytes = minOf(maxFileBytes, MAX_RELATED_BYTES)

    suspend fun collect(changes: List<ChangedFile>): Map<String, List<RelatedFile>> = coroutineScope {
        if (!includeTests && maxRelatedPerChange <= 0) return@coroutineScope emptyMap()
        val changedPaths = changes.flatMap { listOf(it.newPath, it.oldPath) }.toSet()
        val eligible = changes.filter { change ->
            change.status != ChangeStatus.DELETED && pathFilter.allows(change.newPath) && isSourceFile(change.newPath)
        }
        val candidates = eligible.map { change -> async { candidatesFor(change.newPath) } }.awaitAll()
        val plan = assign(candidates, changedPaths)
        plan.map { planned -> async { load(planned) } }
            .awaitAll()
            .filterNotNull()
            .groupBy { it.ownerPath }
    }

    private suspend fun candidatesFor(source: String): Candidates = coroutineScope {
        val tests = async { if (includeTests) existingTests(source) else emptyList() }
        val dependents = async { if (maxRelatedPerChange > 0) dependents(source) else emptyList() }
        val siblings = async { if (maxRelatedPerChange > 0) siblings(source) else emptyList() }
        Candidates(source, tests.await(), dependents.await(), siblings.await())
    }

    private suspend fun existingTests(source: String): List<String> {
        if (TestCandidates.isTestPath(source)) return emptyList()
        val patterned = TestCandidates.forSource(source)
        val indexed = index.filesNamed(TestCandidates.testFileNames(source))
            .sortedByDescending { proximity(source, it) }
        val existing = mutableListOf<String>()
        for (candidate in (patterned + indexed).distinct()) {
            if (existing.size >= MAX_TESTS_PER_CHANGE) break
            if (listings.fileExists(candidate)) existing += candidate
        }
        return existing
    }

    private suspend fun dependents(source: String): List<String> {
        val word = searchableWord(source) ?: return emptyList()
        return index.filesMentioning(word, DEPENDENT_SEARCH_LIMIT)
            .filter { it != source && isSourceFile(it) && !TestCandidates.isTestPath(it) }
            .sortedWith(compareByDescending<String> { proximity(source, it) }.thenBy { it.length })
    }

    private suspend fun siblings(source: String): List<String> {
        val extension = source.substringAfterLast('.', "")
        val stem = source.substringAfterLast('/').substringBeforeLast('.')
        return listings.list(DirectoryListings.parentOf(source)).orEmpty()
            .asSequence()
            .filter { !it.isDirectory && it.path != source && it.name.endsWith(".$extension") }
            .map { it.path }
            .filterNot(TestCandidates::isTestPath)
            .sortedByDescending { sharedPrefixLength(stem, it.substringAfterLast('/')) }
            .toList()
    }

    private fun assign(candidates: List<Candidates>, changedPaths: Set<String>): List<Planned> {
        val claimed = HashSet(changedPaths)
        val plan = mutableListOf<Planned>()

        fun isAvailable(path: String): Boolean =
            path !in claimed && pathFilter.allows(path) && !path.startsWith(CONTEXT_DIRECTORY_PREFIX)

        for (candidate in candidates) {
            if (plan.size >= MAX_RELATED_TOTAL) break
            candidate.tests.filter(::isAvailable).take(MAX_TESTS_PER_CHANGE).forEach { path ->
                claimed += path
                plan += Planned(path, candidate.owner, RelatedKind.TEST)
            }
            val related = candidate.dependents.map { it to RelatedKind.DEPENDENT } +
                candidate.siblings.map { it to RelatedKind.SIBLING }
            related.asSequence()
                .filter { (path, _) -> isAvailable(path) }
                .distinctBy { (path, _) -> path }
                .take(maxRelatedPerChange)
                .forEach { (path, kind) ->
                    claimed += path
                    plan += Planned(path, candidate.owner, kind)
                }
        }
        return plan.take(MAX_RELATED_TOTAL)
    }

    private suspend fun load(planned: Planned): RelatedFile? {
        val content = reader.content(headSha, planned.path, maxBytes) as? FileContent.Text ?: return null
        if (content.value.isBlank()) return null
        return RelatedFile(planned.path, planned.owner, planned.kind, redactor.redact(content.value))
    }

    private fun sharedPrefixLength(first: String, second: String): Int =
        first.zip(second).takeWhile { (a, b) -> a.equals(b, ignoreCase = true) }.size
}
