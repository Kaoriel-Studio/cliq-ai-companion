package com.cliq.plugin.review.settings

import com.cliq.plugin.review.connect.GitRemoteLocation
import com.cliq.plugin.review.provider.ProviderKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class CliqReviewSettingsTest : BasePlatformTestCase() {

    private lateinit var original: CliqReviewSettings.State

    override fun setUp() {
        super.setUp()
        original = CliqReviewSettings.getInstance().snapshot()
    }

    override fun tearDown() {
        try {
            CliqReviewSettings.getInstance().loadState(original)
        } finally {
            super.tearDown()
        }
    }

    fun testStateSurvivesXmlSerialization() {
        val state = CliqReviewSettings.State(
            hosts = mutableListOf(
                CliqReviewSettings.HostEntry("https://gitlab.example.com", ProviderKind.GITLAB.name, tokenStored = true),
                CliqReviewSettings.HostEntry("https://github.com", ProviderKind.GITHUB.name),
            ),
            ignoreProxy = true,
            chunkTokenBudget = 12_345,
            preChangeContent = PreChangeContentMode.ALWAYS.name,
            excludedPatterns = "*.log\nbuild/*",
        )
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), CliqReviewSettings.State::class.java)
        assertEquals(state, restored)
    }

    fun testLoadStateNormalizesHostsAndClampsValues() {
        val settings = CliqReviewSettings.getInstance()
        settings.loadState(
            CliqReviewSettings.State(
                hosts = mutableListOf(
                    CliqReviewSettings.HostEntry(" https://GitHub.com/ ", "GITHUB"),
                    CliqReviewSettings.HostEntry("https://github.com", "GITHUB"),
                    CliqReviewSettings.HostEntry("ftp://nope", "GITLAB"),
                    CliqReviewSettings.HostEntry("https://git.corp", "UNKNOWN"),
                ),
                chunkTokenBudget = 10,
                diffContextLines = -5,
                reviewPromptTemplate = "  ",
            ),
        )
        assertEquals(
            listOf(ReviewHost("https://github.com", ProviderKind.GITHUB), ReviewHost("https://git.corp", ProviderKind.GITLAB)),
            settings.hosts(),
        )
        val state = settings.snapshot()
        assertEquals(CliqReviewSettings.CHUNK_TOKEN_BUDGET_RANGE.first, state.chunkTokenBudget)
        assertEquals(0, state.diffContextLines)
        assertEquals(CliqReviewSettings.DEFAULT_REVIEW_PROMPT_TEMPLATE, state.reviewPromptTemplate)
    }

    fun testFindsHostForRemotePreferringLongestPathPrefix() {
        val store = ReviewHostStore(project, shared = true)
        store.setHosts(
            listOf(
                ReviewHost("https://git.corp", ProviderKind.GITLAB),
                ReviewHost("https://git.corp/gitlab", ProviderKind.GITLAB),
                ReviewHost("https://github.com", ProviderKind.GITHUB),
            ),
        )
        assertEquals(
            "https://git.corp/gitlab",
            store.findHost(GitRemoteLocation("git.corp", null, "gitlab/team/app", isHttps = true))?.url,
        )
        assertEquals("https://git.corp", store.findHost(GitRemoteLocation("git.corp", null, "team/app", isHttps = false))?.url)
        assertEquals(ProviderKind.GITHUB, store.findHost(GitRemoteLocation("github.com", null, "octo/app", isHttps = false))?.kind)
        assertNull(store.findHost(GitRemoteLocation("bitbucket.org", null, "team/app", isHttps = false)))
    }

    fun testHostsAreProjectScopedUnlessSharingIsEnabled() {
        val settings = CliqReviewSettings.getInstance()
        settings.loadState(CliqReviewSettings.State(hosts = mutableListOf(CliqReviewSettings.HostEntry("https://shared.example", "GITLAB"))))
        val local = ReviewHostStore.of(project)
        assertFalse(local.isShared)
        assertTrue(local.hosts().isEmpty())

        local.upsertHost(ReviewHost("https://github.com", ProviderKind.GITHUB))
        assertEquals(listOf("https://github.com"), local.hosts().map { it.url })
        assertEquals(listOf("https://shared.example"), settings.hosts().map { it.url })

        settings.update { it.shareHostsAcrossProjects = true }
        val shared = ReviewHostStore.of(project)
        assertTrue(shared.isShared)
        assertEquals(listOf("https://shared.example"), shared.hosts().map { it.url })
    }

    fun testUpsertAndTokenFlag() {
        val store = ReviewHostStore(project, shared = true)
        store.setHosts(emptyList())
        store.upsertHost(ReviewHost("https://github.com", ProviderKind.GITHUB))
        store.markTokenStored("https://github.com", true)
        assertTrue(store.host("https://github.com")!!.tokenStored)
        store.upsertHost(ReviewHost("https://github.com", ProviderKind.GITHUB, tokenStored = false))
        assertEquals(1, store.hosts().size)
    }

    fun testTokenIsNotReadWhenNoneIsStored() {
        assertNull(ReviewTokens.load(ReviewHost("https://github.com", ProviderKind.GITHUB, tokenStored = false), "shared"))
        assertTrue(ReviewTokens.isWellFormed("ghp_" + "a".repeat(36)))
        assertFalse(ReviewTokens.isWellFormed("has space inside"))
    }

    fun testSecretKeywordsAreNormalized() {
        val settings = CliqReviewSettings.getInstance()
        settings.loadState(CliqReviewSettings.State(secretKeywords = " pass1 \nPWD,pass1;bad word\n;x\ndb.secret"))
        assertEquals(listOf("pass1", "PWD", "db.secret"), settings.collectorOptions().secretKeywords)
        assertEquals(listOf("bad word"), SecretKeywords.invalidEntries("pass1\nbad word"))
    }

    fun testPromptModeFallsBackToBuiltIn() {
        val settings = CliqReviewSettings.getInstance()
        settings.loadState(CliqReviewSettings.State(promptMode = "garbage"))
        assertEquals(ReviewPromptMode.BUILT_IN, settings.collectorOptions().promptMode)
        settings.loadState(CliqReviewSettings.State(promptMode = "NONE"))
        assertEquals(ReviewPromptMode.NONE, settings.collectorOptions().promptMode)
    }

    fun testConfigurableRoundTripKeepsSettings() {
        val settings = CliqReviewSettings.getInstance()
        settings.loadState(
            CliqReviewSettings.State(
                hosts = mutableListOf(CliqReviewSettings.HostEntry("https://github.com", ProviderKind.GITHUB.name)),
                shareHostsAcrossProjects = true,
                chunkTokenBudget = 15_000,
            ),
        )
        val configurable = CliqReviewConfigurable(project)
        try {
            configurable.createComponent()
            configurable.reset()
            assertFalse(configurable.isModified)
            configurable.apply()
            assertEquals(listOf(ReviewHost("https://github.com", ProviderKind.GITHUB)), settings.hosts())
            assertEquals(15_000, settings.snapshot().chunkTokenBudget)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testCollectorOptionsReflectSettings() {
        val settings = CliqReviewSettings.getInstance()
        settings.loadState(CliqReviewSettings.State(maxFileSizeKb = 10, excludedPatterns = "*.txt"))
        val options = settings.collectorOptions()
        assertEquals(10 * 1024, options.maxFileBytes)
        assertFalse(options.pathFilter.allows("notes.txt"))
        assertFalse(options.pathFilter.allows(".env"))
        assertTrue(options.pathFilter.allows("src/App.kt"))
    }
}
