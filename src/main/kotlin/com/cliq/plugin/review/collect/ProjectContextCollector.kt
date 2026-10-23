package com.cliq.plugin.review.collect

import com.cliq.plugin.review.collect.filter.PathFilter
import com.cliq.plugin.review.collect.filter.SecretRedactor
import com.cliq.plugin.review.model.ChangedFile
import com.cliq.plugin.review.model.ConfigFile
import com.cliq.plugin.review.model.DirectoryListing
import com.cliq.plugin.review.model.ProjectContext
import com.cliq.plugin.review.collect.source.DirectoryListings
import com.cliq.plugin.review.collect.source.FileContent
import com.cliq.plugin.review.collect.source.RepositoryReader
import com.cliq.plugin.review.collect.source.TreeEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ProjectContextCollector(
    private val reader: RepositoryReader,
    private val listings: DirectoryListings,
    private val headSha: String,
    private val pathFilter: PathFilter,
    private val redactor: SecretRedactor,
) {

    companion object {
        private const val MAX_SCANNED_DIRECTORIES = 40
        private const val MAX_ROOT_CONFIGS = 8
        private const val MAX_CONFIGS = 16
        private const val MAX_CONFIG_BYTES = 64 * 1024
        private const val MAX_LISTED_ENTRIES = 300
        private const val MAX_WORKFLOWS = 4
        private const val GITHUB_DIRECTORY = ".github"
        private const val GITHUB_WORKFLOWS_DIRECTORY = ".github/workflows"
        private val WORKFLOW_EXTENSIONS = listOf(".yml", ".yaml")

        val CONFIG_FILE_NAMES: Set<String> = setOf(
            "README.md", "README.rst", "README.txt", "README", "CONTRIBUTING.md",
            "AGENTS.md", "CLAUDE.md", "GEMINI.md", ".cursorrules",
            "go.mod", "package.json", "tsconfig.json", "deno.json", "angular.json",
            "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile",
            "Cargo.toml", "Gemfile", "composer.json", "mix.exs", "pubspec.yaml",
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "Makefile", "CMakeLists.txt", "Dockerfile", "docker-compose.yml", "docker-compose.yaml", "compose.yaml",
            ".gitlab-ci.yml", ".editorconfig", ".golangci.yml", ".golangci.yaml", ".rubocop.yml", "detekt.yml",
            ".eslintrc.json", ".eslintrc.js", ".eslintrc.cjs", "eslint.config.js", "eslint.config.mjs",
            "jest.config.js", "jest.config.ts", "vitest.config.ts", "vite.config.ts", "webpack.config.js",
        )

        fun ancestorsNearestFirst(directory: String): List<String> {
            if (directory.isEmpty()) return listOf("")
            val segments = directory.split('/')
            return (segments.size downTo 1).map { segments.take(it).joinToString("/") } + ""
        }
    }

    suspend fun collect(changes: List<ChangedFile>): ProjectContext = coroutineScope {
        val changedPaths = changes.flatMap { listOf(it.newPath, it.oldPath) }.toSet()
        val touched = changes.map { DirectoryListings.parentOf(it.newPath) }.distinct()
        val scanned = touched.flatMap(::ancestorsNearestFirst).distinct().take(MAX_SCANNED_DIRECTORIES)
        val listingsByDirectory = scanned
            .map { directory -> async { directory to listings.list(directory) } }
            .awaitAll()
            .toMap()

        val workflows = ciWorkflows(listingsByDirectory[""], changedPaths)
        val configs = (selectConfigs(scanned, listingsByDirectory, changedPaths) + workflows)
            .map { path -> async { loadConfig(path) } }
            .awaitAll()

        val directories = touched.sorted().mapNotNull { directory ->
            listingsByDirectory[directory]?.let { entries ->
                DirectoryListing(directory, entries.sortedWith(entryOrder).take(MAX_LISTED_ENTRIES), entries.size > MAX_LISTED_ENTRIES)
            }
        }
        ProjectContext(configs, directories)
    }

    private val entryOrder = compareBy<TreeEntry>({ !it.isDirectory }, { it.path })

    private fun selectConfigs(
        scanned: List<String>,
        listingsByDirectory: Map<String, List<TreeEntry>?>,
        changedPaths: Set<String>,
    ): List<String> {
        fun configsIn(directory: String): List<String> =
            listingsByDirectory[directory].orEmpty()
                .filter { !it.isDirectory && it.name in CONFIG_FILE_NAMES }
                .map { it.path }
                .filter { it !in changedPaths && pathFilter.allows(it) }
                .sorted()

        val root = configsIn("").take(MAX_ROOT_CONFIGS)
        val nested = scanned.filter { it.isNotEmpty() }.flatMap(::configsIn)
        return (root + nested).distinct().take(MAX_CONFIGS)
    }

    private suspend fun ciWorkflows(rootEntries: List<TreeEntry>?, changedPaths: Set<String>): List<String> {
        if (rootEntries?.any { it.isDirectory && it.path == GITHUB_DIRECTORY } != true) return emptyList()
        return listings.list(GITHUB_WORKFLOWS_DIRECTORY).orEmpty()
            .filter { !it.isDirectory && WORKFLOW_EXTENSIONS.any(it.name::endsWith) }
            .map { it.path }
            .filter { it !in changedPaths && pathFilter.allows(it) }
            .sorted()
            .take(MAX_WORKFLOWS)
    }

    private suspend fun loadConfig(path: String): ConfigFile =
        when (val content = reader.content(headSha, path, MAX_CONFIG_BYTES)) {
            is FileContent.Text -> ConfigFile(path, redactor.redact(content.value), null)
            FileContent.TooLarge -> ConfigFile(path, null, "too_large")
            FileContent.Binary -> ConfigFile(path, null, "binary")
            FileContent.Missing, FileContent.Unavailable -> ConfigFile(path, null, "unavailable")
        }
}
