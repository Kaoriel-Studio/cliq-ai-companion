package com.cliq.plugin.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider

enum class ShellFlavor { POSIX, CMD, POWERSHELL }

object ShellQuoting {

    private val POSIX_NEEDS_QUOTING = charArrayOf(
        ' ', '\t', '"', '\'', '\\', '$', '`', '!', '*', '?', '[', ']', '(', ')',
        '{', '}', '<', '>', '|', '&', ';', '#', '~', '\n',
    )

    private val CMD_NEEDS_QUOTING = charArrayOf(
        ' ', '\t', '"', '&', '|', '<', '>', '^', '(', ')', '%', '!', ',', ';', '=', '\n',
    )

    private val POWERSHELL_NEEDS_QUOTING = charArrayOf(
        ' ', '\t', '"', '\'', '`', '$', '&', '|', '<', '>', '(', ')', '{', '}', '[', ']',
        ';', ',', '@', '#', '\n',
    )

    fun osDefault(): ShellFlavor = if (SystemInfo.isWindows) ShellFlavor.CMD else ShellFlavor.POSIX

    fun detect(project: Project?): ShellFlavor {
        if (project == null || project.isDisposed) return osDefault()
        val shellPath = runCatching {
            TerminalProjectOptionsProvider.getInstance(project).shellPath
        }.getOrNull().orEmpty()
        return classify(shellPath)
    }

    fun classify(shellPath: String): ShellFlavor {
        val executable = shellPath
            .trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBefore(' ')
            .removeSuffix(".exe")
            .lowercase()

        return when {
            executable == "pwsh" || executable == "powershell" -> ShellFlavor.POWERSHELL
            executable == "cmd" -> ShellFlavor.CMD
            executable.isEmpty() -> osDefault()
            else -> ShellFlavor.POSIX
        }
    }

    fun quote(value: String, flavor: ShellFlavor): String = when (flavor) {
        ShellFlavor.POSIX -> quotePosix(value)
        ShellFlavor.CMD -> quoteCmd(value)
        ShellFlavor.POWERSHELL -> quotePowerShell(value)
    }

    fun command(executable: String, arguments: List<String>, flavor: ShellFlavor): String {
        val head = quote(executable, flavor)
        if (arguments.isEmpty()) return head
        return arguments.joinToString(separator = " ", prefix = "$head ") { quote(it, flavor) }
    }

    private fun quotePosix(value: String): String {
        if (value.isEmpty()) return "''"
        if (value.none { it in POSIX_NEEDS_QUOTING }) return value

        val builder = StringBuilder(value.length + 4)
        builder.append('\'')
        for (char in value) {
            if (char == '\'') builder.append("'\\''") else builder.append(char)
        }
        builder.append('\'')
        return builder.toString()
    }

    private fun quoteCmd(value: String): String {
        if (value.isEmpty()) return "\"\""
        if (value.none { it in CMD_NEEDS_QUOTING }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun quotePowerShell(value: String): String {
        if (value.isEmpty()) return "''"
        if (value.none { it in POWERSHELL_NEEDS_QUOTING }) return value
        return "'" + value.replace("'", "''") + "'"
    }
}
