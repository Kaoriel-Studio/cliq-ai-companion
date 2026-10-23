package com.cliq.plugin.agents

import java.util.UUID

data class CliAgentDefinition(
    var id: String = UUID.randomUUID().toString(),
    var displayName: String = "",
    var executablePath: String = "",
    var argumentsTemplate: String = "",
    var environmentKeys: MutableList<String> = mutableListOf(),
    var environmentVariables: MutableMap<String, String> = mutableMapOf(),
    var workingDirectory: String = "",
) {
    fun isValid(): Boolean = displayName.isNotBlank() && executablePath.isNotBlank()

    fun deepCopy(): CliAgentDefinition = copy(
        environmentKeys = environmentKeys.toMutableList(),
        environmentVariables = environmentVariables.toMutableMap(),
    )

    companion object {
        const val CLAUDE_ID = "claude"
        const val GEMINI_ID = "gemini"
        const val QWEN_ID = "qwen"
    }
}
