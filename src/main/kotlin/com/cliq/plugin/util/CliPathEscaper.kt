package com.cliq.plugin.util

object CliPathEscaper {

    fun escape(path: String): String = ShellQuoting.quote(path, ShellQuoting.osDefault())

    fun escape(path: String, flavor: ShellFlavor): String = ShellQuoting.quote(path, flavor)
}
