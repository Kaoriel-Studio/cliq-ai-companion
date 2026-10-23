package com.cliq.plugin.review.collect.filter

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactorTest {

    @Test
    fun redactsKnownTokenEverywhere() {
        val redactor = SecretRedactor(listOf("my-personal-token-123"))
        val result = redactor.redact("a my-personal-token-123 b my-personal-token-123")
        assertFalse(result.contains("my-personal-token-123"))
        assertEquals(2, redactor.redactionCount)
    }

    @Test
    fun redactsPrivateKeyBlocks() {
        val text = "before\n-----BEGIN OPENSSH PRIVATE KEY-----\nabc\ndef\n-----END OPENSSH PRIVATE KEY-----\nafter"
        val result = SecretRedactor().redact(text)
        assertEquals("before\n[REDACTED:private-key]\nafter", result)
    }

    @Test
    fun redactsWellKnownTokenFormats() {
        val samples = listOf(
            "AKIAIOSFODNN7EXAMPLE",
            "glpat-abcdefghijklmnopqrst1234",
            "ghp_" + "a".repeat(36),
            "xoxb-1234567890-abcdefghij",
            "AIza" + "B".repeat(35),
            "sk_live_" + "c".repeat(24),
            "sk-ant-" + "d".repeat(40),
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
        )
        samples.forEach { secret ->
            val result = SecretRedactor().redact("value = $secret;")
            assertFalse(result.contains(secret), secret)
            assertTrue(result.contains("[REDACTED:"), secret)
        }
    }

    @Test
    fun keepsAssignmentNameButRedactsLiteralValue() {
        val result = SecretRedactor().redact("val dbPassword = \"s3cr3t-value!\"")
        assertEquals("val dbPassword = [REDACTED:secret-assignment]", result)
    }

    @Test
    fun keepsEnvironmentReferencesAndShortValues() {
        val text = "password: \"\${DB_PASSWORD}\"\napiKey = \"short\"\nval token = readToken()"
        assertEquals(text, SecretRedactor().redact(text))
    }

    @Test
    fun redactsCredentialsEmbeddedInUrls() {
        val result = SecretRedactor().redact("url=https://deploy:hunter2secret@registry.example.com/repo")
        assertEquals("url=https://[REDACTED:url-credentials]registry.example.com/repo", result)
    }

    @Test
    fun handlesLongIdentifierRunsQuickly() {
        val noisy = ("a-b_c.".repeat(20_000)) + "password"
        val started = System.nanoTime()
        SecretRedactor().redact(noisy)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis < 2_000, "redaction took $elapsedMillis ms")
    }

    @Test
    fun redactsValuesOfCustomKeywords() {
        val redactor = SecretRedactor(customKeywords = listOf("pass1", "db.pwd"))
        val text = "val pass1 = \"abc123\"\nDB.PWD: hunter22\nuserPass1=\"x9y8\"\nother = \"visible\"\npass1 = compute(x)"
        val result = redactor.redact(text)
        assertEquals(
            "val pass1 = [REDACTED:custom-secret]\nDB.PWD: [REDACTED:custom-secret]\nuserPass1=[REDACTED:custom-secret]\nother = \"visible\"\npass1 = compute(x)",
            result,
        )
    }

    @Test
    fun ignoresCustomKeywordsThatAreNotAssigned() {
        val text = "// the pass1 flag is documented here\nif (pass1) run()"
        assertEquals(text, SecretRedactor(customKeywords = listOf("pass1")).redact(text))
    }
}
