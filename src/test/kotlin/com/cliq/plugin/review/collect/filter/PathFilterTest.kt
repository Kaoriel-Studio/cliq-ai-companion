package com.cliq.plugin.review.collect.filter

import com.cliq.plugin.review.settings.CliqReviewSettings
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathFilterTest {

    private val filter = PathFilter(PathPatternSet.parseLines(CliqReviewSettings.DEFAULT_EXCLUDED_PATTERNS))

    @Test
    fun alwaysExcludesSecretsEvenWithoutUserPatterns() {
        val permissive = PathFilter(emptyList())
        listOf(
            ".env",
            "deploy/.env.production",
            "config/prod.env",
            "certs/server.KEY",
            "keys/id_ed25519",
            "app/config/secrets.yml",
            "home/.ssh/config",
            "infra/terraform.tfstate",
            ".npmrc",
            "nested/.docker/config.json",
        ).forEach { assertEquals(PathVerdict.SENSITIVE, permissive.verdict(it), it) }
    }

    @Test
    fun excludesDefaultNoisePatterns() {
        listOf(
            "package-lock.json",
            "web/yarn.lock",
            "static/app.min.js",
            "docs/logo.png",
            "build/generated/Foo.kt",
            "packages/ui/node_modules/react/index.js",
            "app/build/outputs/x.txt",
            ".cliq/reviews/pr-1/pr_1_meta.xml",
        ).forEach { assertEquals(PathVerdict.EXCLUDED, filter.verdict(it), it) }
    }

    @Test
    fun allowsRegularSources() {
        listOf("src/main/kotlin/App.kt", "README.md", "lib/builder/Build.kt", "envelope.txt", "docs/environment.md")
            .forEach { assertNull(filter.verdict(it), it) }
    }

    @Test
    fun rejectsUnsafePaths() {
        listOf("../etc/passwd", "/abs/path", "a/../b", "-rf", "a//b", "a/\u0000b")
            .forEach { assertEquals(PathVerdict.UNSAFE, filter.verdict(it), it) }
    }

    @Test
    fun supportsDoubleStarAndAnchoredPathPatterns() {
        val patterns = PathPatternSet(listOf("generated/**", "docs/*.md", "**/fixtures/*.json"))
        assertTrue(patterns.matches("module/generated/deep/File.kt"))
        assertTrue(patterns.matches("docs/guide.md"))
        assertTrue(patterns.matches("site/docs/guide.md"))
        assertFalse(patterns.matches("docs/nested/guide.md"))
        assertTrue(patterns.matches("src/test/fixtures/data.json"))
    }

    @Test
    fun ignoresBlankLinesAndCommentsWhenParsing() {
        assertEquals(listOf("*.log", "tmp/*"), PathPatternSet.parseLines("\n# noise\n *.log \n\ntmp/*\n"))
    }
}
