package com.cliq.plugin.util

import org.junit.Test
import kotlin.test.assertEquals

class ShellQuotingTest {

    @Test
    fun posixQuotesSpacesAndMetacharacters() {
        assertEquals("plain.txt", ShellQuoting.quote("plain.txt", ShellFlavor.POSIX))
        assertEquals("'with space.txt'", ShellQuoting.quote("with space.txt", ShellFlavor.POSIX))
        assertEquals("'\$(whoami)'", ShellQuoting.quote("\$(whoami)", ShellFlavor.POSIX))
        assertEquals("'a;rm -rf /'", ShellQuoting.quote("a;rm -rf /", ShellFlavor.POSIX))
    }

    @Test
    fun posixEscapesEmbeddedSingleQuote() {
        assertEquals("'it'\\''s'", ShellQuoting.quote("it's", ShellFlavor.POSIX))
    }

    @Test
    fun cmdUsesDoubleQuotesNotSingleQuotes() {
        assertEquals("\"with space.txt\"", ShellQuoting.quote("with space.txt", ShellFlavor.CMD))
        assertEquals("\"a&b\"", ShellQuoting.quote("a&b", ShellFlavor.CMD))
        assertEquals("plain.txt", ShellQuoting.quote("plain.txt", ShellFlavor.CMD))
    }

    @Test
    fun powerShellDoublesSingleQuotes() {
        assertEquals("'it''s'", ShellQuoting.quote("it's", ShellFlavor.POWERSHELL))
        assertEquals("'with space.txt'", ShellQuoting.quote("with space.txt", ShellFlavor.POWERSHELL))
    }

    @Test
    fun emptyValueIsQuotedForEveryFlavor() {
        assertEquals("''", ShellQuoting.quote("", ShellFlavor.POSIX))
        assertEquals("\"\"", ShellQuoting.quote("", ShellFlavor.CMD))
        assertEquals("''", ShellQuoting.quote("", ShellFlavor.POWERSHELL))
    }

    @Test
    fun buildsCommandWithQuotedArguments() {
        val command = ShellQuoting.command(
            "/opt/my agents/claude",
            listOf("--model", "sonnet", "--note", "a b"),
            ShellFlavor.POSIX,
        )
        assertEquals("'/opt/my agents/claude' --model sonnet --note 'a b'", command)
    }

    @Test
    fun classifiesShellsByExecutableName() {
        assertEquals(ShellFlavor.POWERSHELL, ShellQuoting.classify("C:\\Program Files\\PowerShell\\pwsh.exe"))
        assertEquals(ShellFlavor.POWERSHELL, ShellQuoting.classify("powershell.exe"))
        assertEquals(ShellFlavor.CMD, ShellQuoting.classify("C:\\Windows\\System32\\cmd.exe"))
        assertEquals(ShellFlavor.POSIX, ShellQuoting.classify("/bin/zsh"))
        assertEquals(ShellFlavor.POSIX, ShellQuoting.classify("/usr/local/bin/fish"))
        assertEquals(ShellFlavor.POSIX, ShellQuoting.classify("/bin/bash --login"))
    }
}
