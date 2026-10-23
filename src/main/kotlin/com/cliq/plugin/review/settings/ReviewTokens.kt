package com.cliq.plugin.review.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread

object ReviewTokens {

    private const val SUBSYSTEM = "Cliq Code Review"
    private const val MIN_LENGTH = 8
    private const val MAX_LENGTH = 512

    private val log = logger<ReviewTokens>()

    private fun attributes(host: ReviewHost, scope: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SUBSYSTEM, "$scope|${host.kind.name}@${host.url}"))

    fun isWellFormed(token: CharSequence): Boolean =
        token.length in MIN_LENGTH..MAX_LENGTH && token.all { it.code in 0x21..0x7E }

    @RequiresBackgroundThread
    fun load(host: ReviewHost, scope: String): String? {
        if (!host.tokenStored) return null
        return try {
            PasswordSafe.instance.getPassword(attributes(host, scope))?.takeIf(::isWellFormed)
        } catch (e: RuntimeException) {
            log.warn("Failed to read a code review token from the password safe: ${e.javaClass.simpleName}")
            null
        }
    }

    @RequiresBackgroundThread
    fun store(host: ReviewHost, scope: String, token: String): Boolean {
        require(isWellFormed(token)) { "Malformed token" }
        return try {
            PasswordSafe.instance.setPassword(attributes(host, scope), token)
            true
        } catch (e: RuntimeException) {
            log.warn("Failed to store a code review token in the password safe: ${e.javaClass.simpleName}")
            false
        }
    }

    @RequiresBackgroundThread
    fun forget(host: ReviewHost, scope: String) {
        try {
            PasswordSafe.instance.setPassword(attributes(host, scope), null)
        } catch (e: RuntimeException) {
            log.warn("Failed to remove a code review token from the password safe: ${e.javaClass.simpleName}")
        }
    }
}
