package com.cliq.plugin.review.settings

import com.cliq.plugin.review.collect.budget.ChunkBudget
import com.cliq.plugin.review.collect.filter.PathFilter
import com.cliq.plugin.review.collect.filter.PathPatternSet
import com.cliq.plugin.review.connect.HostUrls
import com.cliq.plugin.review.http.ConnectionOptions
import com.cliq.plugin.review.provider.ProviderKind
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.time.Duration

data class ReviewHost(
    val url: String,
    val kind: ProviderKind,
    val tokenStored: Boolean = false,
) {
    val label: String get() = "${kind.displayName}: $url"
}

@State(
    name = "CliqReviewSettings",
    storages = [Storage("cliq-review.xml")],
)
@Service(Service.Level.APP)
class CliqReviewSettings : PersistentStateComponent<CliqReviewSettings.State> {

    data class HostEntry(
        var url: String = "",
        var kind: String = ProviderKind.GITLAB.name,
        var tokenStored: Boolean = false,
    )

    data class State(
        var hosts: MutableList<HostEntry> = mutableListOf(),
        var shareHostsAcrossProjects: Boolean = false,
        var ignoreProxy: Boolean = false,
        var requestTimeoutSeconds: Int = DEFAULT_REQUEST_TIMEOUT_SECONDS,
        var chunkTokenBudget: Int = DEFAULT_CHUNK_TOKEN_BUDGET,
        var chunkLineBudget: Int = DEFAULT_CHUNK_LINE_BUDGET,
        var diffContextLines: Int = DEFAULT_DIFF_CONTEXT_LINES,
        var maxFileSizeKb: Int = DEFAULT_MAX_FILE_SIZE_KB,
        var maxRelatedFilesPerChange: Int = DEFAULT_MAX_RELATED_FILES_PER_CHANGE,
        var includeRelatedTests: Boolean = true,
        var includeFailedJobLogs: Boolean = true,
        var fetchMissingCommits: Boolean = true,
        var gitIgnoreContextDirectory: Boolean = true,
        var preChangeContent: String = PreChangeContentMode.REWRITES_ONLY.name,
        var excludedPatterns: String = DEFAULT_EXCLUDED_PATTERNS,
        var reviewPromptTemplate: String = DEFAULT_REVIEW_PROMPT_TEMPLATE,
        var promptMode: String = ReviewPromptMode.BUILT_IN.name,
        var promptTemplateId: String = "",
        var secretKeywords: String = "",
    ) {
        fun deepCopy(): State = copy(hosts = hosts.map { it.copy() }.toMutableList())

        fun copyEditableFrom(source: State) {
            shareHostsAcrossProjects = source.shareHostsAcrossProjects
            promptMode = source.promptMode
            promptTemplateId = source.promptTemplateId
            secretKeywords = source.secretKeywords
            ignoreProxy = source.ignoreProxy
            requestTimeoutSeconds = source.requestTimeoutSeconds
            chunkTokenBudget = source.chunkTokenBudget
            chunkLineBudget = source.chunkLineBudget
            diffContextLines = source.diffContextLines
            maxFileSizeKb = source.maxFileSizeKb
            maxRelatedFilesPerChange = source.maxRelatedFilesPerChange
            includeRelatedTests = source.includeRelatedTests
            includeFailedJobLogs = source.includeFailedJobLogs
            fetchMissingCommits = source.fetchMissingCommits
            gitIgnoreContextDirectory = source.gitIgnoreContextDirectory
            preChangeContent = source.preChangeContent
            excludedPatterns = source.excludedPatterns
            reviewPromptTemplate = source.reviewPromptTemplate
        }

        fun normalized(): State = deepCopy().apply {
            hosts = hosts.mapNotNull { entry ->
                HostUrls.normalize(entry.url)?.let { url ->
                    HostEntry(url, ProviderKind.parse(entry.kind).name, entry.tokenStored)
                }
            }.distinctBy { it.url }.toMutableList()
            requestTimeoutSeconds = requestTimeoutSeconds.coerceIn(REQUEST_TIMEOUT_RANGE)
            chunkTokenBudget = chunkTokenBudget.coerceIn(CHUNK_TOKEN_BUDGET_RANGE)
            chunkLineBudget = chunkLineBudget.coerceIn(CHUNK_LINE_BUDGET_RANGE)
            diffContextLines = diffContextLines.coerceIn(DIFF_CONTEXT_LINES_RANGE)
            maxFileSizeKb = maxFileSizeKb.coerceIn(MAX_FILE_SIZE_KB_RANGE)
            maxRelatedFilesPerChange = maxRelatedFilesPerChange.coerceIn(RELATED_FILES_RANGE)
            preChangeContent = PreChangeContentMode.parse(preChangeContent).name
            reviewPromptTemplate = reviewPromptTemplate.ifBlank { DEFAULT_REVIEW_PROMPT_TEMPLATE }
            promptMode = ReviewPromptMode.parse(promptMode).name
            secretKeywords = SecretKeywords.normalize(secretKeywords).joinToString("\n")
        }
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 60
        const val DEFAULT_CHUNK_TOKEN_BUDGET = 20_000
        const val DEFAULT_CHUNK_LINE_BUDGET = 1_800
        const val DEFAULT_DIFF_CONTEXT_LINES = 20
        const val DEFAULT_MAX_FILE_SIZE_KB = 256
        const val DEFAULT_MAX_RELATED_FILES_PER_CHANGE = 3

        val REQUEST_TIMEOUT_RANGE = 5..600
        val CHUNK_TOKEN_BUDGET_RANGE = 2_000..500_000
        val CHUNK_LINE_BUDGET_RANGE = 200..50_000
        val DIFF_CONTEXT_LINES_RANGE = 0..500
        val MAX_FILE_SIZE_KB_RANGE = 1..10_240
        val RELATED_FILES_RANGE = 0..20

        val DEFAULT_EXCLUDED_PATTERNS: String = listOf(
            "*.lock", "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "Gemfile.lock", "Cargo.lock",
            "poetry.lock", "composer.lock", "go.sum",
            "*.min.js", "*.min.css", "*.bundle.js", "*.map",
            "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.ico", "*.svg", "*.pdf",
            "*.zip", "*.tar", "*.gz", "*.jar", "*.class",
            "*.woff", "*.woff2", "*.ttf", "*.mp4", "*.mp3", "*.wav",
            "dist/*", "build/*", "target/*", "out/*", "node_modules/*", "vendor/*",
            ".next/*", ".nuxt/*", "__pycache__/*", ".cliq/*",
        ).joinToString("\n")

        const val PLACEHOLDER_KIND = "kind"
        const val PLACEHOLDER_REFERENCE = "reference"
        const val PLACEHOLDER_NUMBER = "number"
        const val PLACEHOLDER_TITLE = "title"
        const val PLACEHOLDER_URL = "url"
        const val PLACEHOLDER_SOURCE_BRANCH = "sourceBranch"
        const val PLACEHOLDER_TARGET_BRANCH = "targetBranch"
        const val PLACEHOLDER_CHUNK_COUNT = "chunkCount"
        const val PLACEHOLDER_CONTEXT_DIR = "contextDir"
        const val PLACEHOLDER_META_FILE = "metaFile"
        const val PLACEHOLDER_PROJECT_FILE = "projectFile"
        const val PLACEHOLDER_REPORT_FILE = "reportFile"

        val PLACEHOLDERS = listOf(
            PLACEHOLDER_KIND, PLACEHOLDER_REFERENCE, PLACEHOLDER_NUMBER, PLACEHOLDER_TITLE, PLACEHOLDER_URL,
            PLACEHOLDER_SOURCE_BRANCH, PLACEHOLDER_TARGET_BRANCH, PLACEHOLDER_CHUNK_COUNT, PLACEHOLDER_CONTEXT_DIR,
            PLACEHOLDER_META_FILE, PLACEHOLDER_PROJECT_FILE, PLACEHOLDER_REPORT_FILE,
        )

        val DEFAULT_REVIEW_PROMPT_TEMPLATE: String = """
            Act as a senior code reviewer. Perform a strict, critical review of {{$PLACEHOLDER_KIND}} {{$PLACEHOLDER_REFERENCE}} "{{$PLACEHOLDER_TITLE}}" ({{$PLACEHOLDER_SOURCE_BRANCH}} -> {{$PLACEHOLDER_TARGET_BRANCH}}).

            The attached Cliq context files in {{$PLACEHOLDER_CONTEXT_DIR}} describe it completely. Read all of them, in this order, before answering:
            - {{$PLACEHOLDER_META_FILE}}: description, CI status and failed job logs, review discussions, list of changed and skipped files
            - {{$PLACEHOLDER_PROJECT_FILE}}: build configuration, commits and listings of the touched directories
            - {{$PLACEHOLDER_CHUNK_COUNT}} chunk file(s): per-file diffs, full file contents, related tests and dependent files
            Review the change itself. Use related files and tests only to judge its impact.

            Rules:
            1. Only negative findings: bugs, security vulnerabilities, race conditions, broken edge cases, anti-patterns, performance problems, missing or weak tests. No praise and no summary of what the change does.
            2. Group findings by severity, most severe first: Critical (crashes, severe security flaws, data loss), High (broken logic, race conditions, major performance issues), Medium (bad practices, unhandled edge cases, maintainability), Low (style, naming, minor optimizations). Skip empty groups.
            3. For every finding give: Location (file path and line numbers in the new version, e.g. path/to/File.kt:42-45), Problem (what is wrong and why it matters, in plain everyday language), Solution (a direct fix or a short code snippet).
            4. Report only what you can point to in the attached code. If you are not sure, label the finding "Uncertain" and say what to check.
            5. Take existing review discussions and CI results into account: do not repeat comments that are already resolved, and explain any CI failure that the change causes.
            6. Write the entire report in Russian.

            Save the report to {{$PLACEHOLDER_REPORT_FILE}} in the project root. Do not modify any other file.
        """.trimIndent()

        @JvmStatic
        fun getInstance(): CliqReviewSettings =
            ApplicationManager.getApplication().getService(CliqReviewSettings::class.java)
    }

    private val lock = Any()

    @Volatile
    private var myState = State()

    fun snapshot(): State = synchronized(lock) { myState.deepCopy() }

    fun update(mutation: (State) -> Unit) {
        synchronized(lock) {
            val next = myState.deepCopy()
            mutation(next)
            myState = next.normalized()
        }
    }

    fun hosts(): List<ReviewHost> = myState.hosts.map { ReviewHost(it.url, ProviderKind.parse(it.kind), it.tokenStored) }

    fun setHosts(hosts: List<ReviewHost>) {
        update { state -> state.hosts = hosts.map { HostEntry(it.url, it.kind.name, it.tokenStored) }.toMutableList() }
    }

    val shareHostsAcrossProjects: Boolean get() = myState.shareHostsAcrossProjects

    fun connectionOptions(): ConnectionOptions {
        val state = snapshot()
        return ConnectionOptions(Duration.ofSeconds(state.requestTimeoutSeconds.toLong()), state.ignoreProxy)
    }

    fun collectorOptions(): ReviewCollectorOptions {
        val state = snapshot()
        return ReviewCollectorOptions(
            chunkBudget = ChunkBudget(tokens = state.chunkTokenBudget, lines = state.chunkLineBudget),
            diffContextLines = state.diffContextLines,
            maxFileBytes = state.maxFileSizeKb * 1024,
            pathFilter = PathFilter(PathPatternSet.parseLines(state.excludedPatterns)),
            includeRelatedTests = state.includeRelatedTests,
            maxRelatedFilesPerChange = state.maxRelatedFilesPerChange,
            preChangeContent = PreChangeContentMode.parse(state.preChangeContent),
            includeFailedJobLogs = state.includeFailedJobLogs,
            fetchMissingCommits = state.fetchMissingCommits,
            gitIgnoreContextDirectory = state.gitIgnoreContextDirectory,
            reviewPromptTemplate = state.reviewPromptTemplate,
            promptMode = ReviewPromptMode.parse(state.promptMode),
            promptTemplateId = state.promptTemplateId,
            secretKeywords = SecretKeywords.normalize(state.secretKeywords),
        )
    }

    override fun getState(): State = snapshot()

    override fun loadState(state: State) {
        synchronized(lock) { myState = state.normalized() }
    }
}

data class ReviewCollectorOptions(
    val chunkBudget: ChunkBudget,
    val diffContextLines: Int,
    val maxFileBytes: Int,
    val pathFilter: PathFilter,
    val includeRelatedTests: Boolean,
    val maxRelatedFilesPerChange: Int,
    val preChangeContent: PreChangeContentMode,
    val includeFailedJobLogs: Boolean,
    val fetchMissingCommits: Boolean,
    val gitIgnoreContextDirectory: Boolean,
    val reviewPromptTemplate: String,
    val promptMode: ReviewPromptMode,
    val promptTemplateId: String,
    val secretKeywords: List<String>,
)
