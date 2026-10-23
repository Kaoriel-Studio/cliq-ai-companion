package com.cliq.plugin.terminal

import com.cliq.plugin.mcp.CliqIdeServer
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer

class CliqTerminalEnvCustomizer : LocalTerminalCustomizer() {

    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<String>,
        env: MutableMap<String, String>,
    ): Array<String> {
        val pending = project.service<CliqTerminalSessions>().consumePendingLaunch() ?: return command

        pending.environment.forEach { (key, value) -> env[key] = value }

        val server = project.service<CliqIdeServer>()
        server.port()?.let { env["GEMINI_CLI_IDE_SERVER_PORT"] = it.toString() }
        env["GEMINI_CLI_IDE_AUTH_TOKEN"] = server.authToken()
        if (server.workspacePath().isNotEmpty()) {
            env["GEMINI_CLI_IDE_WORKSPACE_PATH"] = server.workspacePath()
        }
        return command
    }
}
