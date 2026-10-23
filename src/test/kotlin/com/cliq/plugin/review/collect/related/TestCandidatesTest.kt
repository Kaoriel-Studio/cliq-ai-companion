package com.cliq.plugin.review.collect.related

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestCandidatesTest {

    @Test
    fun mirrorsMavenLayoutForJvmSources() {
        val candidates = TestCandidates.forSource("service/src/main/kotlin/com/acme/UserService.kt")
        assertTrue("service/src/test/kotlin/com/acme/UserServiceTest.kt" in candidates)
        assertTrue("service/src/main/kotlin/com/acme/UserServiceTest.kt" in candidates)
    }

    @Test
    fun mirrorsMultiplatformSourceSets() {
        val candidates = TestCandidates.forSource("shared/src/commonMain/kotlin/Repo.kt")
        assertTrue("shared/src/commonTest/kotlin/RepoTest.kt" in candidates)
    }

    @Test
    fun suggestsConventionalNamesPerLanguage() {
        assertTrue("pkg/api/handler_test.go" in TestCandidates.forSource("pkg/api/handler.go"))
        assertTrue("tests/test_models.py" in TestCandidates.forSource("app/models.py"))
        assertTrue("src/ui/__tests__/Button.test.tsx" in TestCandidates.forSource("src/ui/Button.tsx"))
        assertTrue("spec/models/user_spec.rb" in TestCandidates.forSource("app/models/user.rb"))
    }

    @Test
    fun skipsFilesThatAreAlreadyTests() {
        assertEquals(emptyList(), TestCandidates.forSource("src/test/kotlin/FooTest.kt"))
        assertEquals(emptyList(), TestCandidates.forSource("web/button.spec.ts"))
    }

    @Test
    fun recognisesTestPaths() {
        assertTrue(TestCandidates.isTestPath("src/test/java/FooTest.java"))
        assertTrue(TestCandidates.isTestPath("pkg/handler_test.go"))
        assertTrue(TestCandidates.isTestPath("test_utils.py"))
        assertFalse(TestCandidates.isTestPath("src/main/kotlin/Contest.kt"))
        assertFalse(TestCandidates.isTestPath("src/latest/Thing.kt"))
    }

    @Test
    fun choosesSearchableWordsCarefully() {
        assertEquals("UserService", RelatedFilesCollector.searchableWord("src/UserService.kt"))
        assertNull(RelatedFilesCollector.searchableWord("src/index.ts"))
        assertNull(RelatedFilesCollector.searchableWord("src/user-service.ts"))
        assertNull(RelatedFilesCollector.searchableWord("src/db.go"))
    }

    @Test
    fun measuresDirectoryProximity() {
        assertEquals(2, RelatedFilesCollector.proximity("a/b/c/X.kt", "a/b/d/Y.kt"))
        assertEquals(0, RelatedFilesCollector.proximity("x/X.kt", "y/Y.kt"))
    }
}
