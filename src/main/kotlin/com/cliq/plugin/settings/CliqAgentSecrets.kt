package com.cliq.plugin.settings

import com.cliq.plugin.agents.CliAgentDefinition
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.logger

object CliqAgentSecrets {

    private const val SUBSYSTEM = "Cliq CLI Agent Environment"

    private val log = logger<CliqAgentSecrets>()

    private fun attributes(agentId: String, key: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SUBSYSTEM, "$agentId/$key"))

    fun store(agentId: String, environment: Map<String, String>) {
        runCatching {
            environment.forEach { (key, value) ->
                PasswordSafe.instance.set(attributes(agentId, key), Credentials(key, value))
            }
        }.onFailure { log.warn("Failed to store environment for agent $agentId", it) }
    }

    fun forget(agentId: String, keys: Collection<String>) {
        runCatching {
            keys.forEach { PasswordSafe.instance.set(attributes(agentId, it), null) }
        }.onFailure { log.warn("Failed to remove environment for agent $agentId", it) }
    }

    fun load(agent: CliAgentDefinition): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        agent.environmentKeys.forEach { key ->
            val value = runCatching {
                PasswordSafe.instance.get(attributes(agent.id, key))?.getPasswordAsString()
            }.getOrNull()
            if (!value.isNullOrEmpty()) result[key] = value
        }
        agent.environmentVariables.forEach { (key, value) -> result.putIfAbsent(key, value) }
        return result
    }

    fun migrateLegacyValues(agent: CliAgentDefinition): Boolean {
        if (agent.environmentVariables.isEmpty()) return false
        val legacy = agent.environmentVariables.toMap()
        store(agent.id, legacy)
        val keys = agent.environmentKeys.toMutableList()
        legacy.keys.forEach { if (!keys.contains(it)) keys.add(it) }
        agent.environmentKeys = keys
        agent.environmentVariables = mutableMapOf()
        return true
    }
}
