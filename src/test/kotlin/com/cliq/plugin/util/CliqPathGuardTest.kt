package com.cliq.plugin.util

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CliqPathGuardTest {

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("cliq_guard_root")
        try {
            block(root.toRealPath())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun acceptsExistingFileInsideRoot() = withRoot { root ->
        val file = Files.createFile(root.resolve("inside.txt"))
        val resolved = CliqPathGuard.resolveInsideRoots(listOf(root), file.toString())
        assertNotNull(resolved)
        assertEquals(file.toRealPath(), resolved)
    }

    @Test
    fun acceptsNotYetCreatedFileInsideRoot() = withRoot { root ->
        val target = root.resolve("nested/dir/new-file.txt")
        val resolved = CliqPathGuard.resolveInsideRoots(listOf(root), target.toString())
        assertNotNull(resolved)
    }

    @Test
    fun rejectsPathOutsideRoot() = withRoot { root ->
        val outside = Files.createTempDirectory("cliq_guard_outside")
        try {
            val target = outside.resolve("evil.txt")
            assertNull(CliqPathGuard.resolveInsideRoots(listOf(root), target.toString()))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsParentTraversalEscape() = withRoot { root ->
        val escaping = root.resolve("../../etc/passwd").toString()
        assertNull(CliqPathGuard.resolveInsideRoots(listOf(root), escaping))
    }

    @Test
    fun normalizesInnerTraversalThatStaysInsideRoot() = withRoot { root ->
        val inner = root.resolve("a/../b.txt").toString()
        val resolved = CliqPathGuard.resolveInsideRoots(listOf(root), inner)
        assertNotNull(resolved)
        assertEquals(root.resolve("b.txt"), resolved)
    }

    @Test
    fun rejectsRelativePaths() = withRoot { root ->
        assertNull(CliqPathGuard.resolveInsideRoots(listOf(root), "relative/file.txt"))
    }

    @Test
    fun rejectsEverythingWhenNoRootsConfigured() {
        assertNull(CliqPathGuard.resolveInsideRoots(emptyList(), "/tmp/anything.txt"))
    }

    @Test
    fun rejectsSymlinkEscapingTheRoot() = withRoot { root ->
        val outside = Files.createTempDirectory("cliq_guard_symlink_target")
        try {
            val secret = Files.createFile(outside.resolve("secret.txt"))
            val link = root.resolve("link.txt")
            try {
                Files.createSymbolicLink(link, secret)
            } catch (unsupported: Exception) {
                return@withRoot
            }
            assertNull(CliqPathGuard.resolveInsideRoots(listOf(root), link.toString()))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }
}
