package com.cliq.plugin.review.collect.output

import com.cliq.plugin.review.collect.render.ContextFileNames
import com.cliq.plugin.review.collect.render.RenderedChunk
import com.cliq.plugin.review.collect.render.RenderedContext
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewContextStorageTest {

    private fun rendered(prefix: String, number: Long, chunkCount: Int): RenderedContext {
        val names = ContextFileNames(prefix, number)
        val chunks = (1..chunkCount).map { RenderedChunk(it, names.chunk(it), "<chunk index=\"$it\"/>", 10) }
        return RenderedContext(names, "<meta/>", "<project/>", chunks)
    }

    private fun withProject(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("cliq_storage").toRealPath()
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun writesContextFilesWithGitIgnore() = withProject { project ->
        val stored = ReviewContextStorage.write(project, rendered("mr", 12, 2), gitIgnoreContextRoot = true)
        assertEquals(project.resolve(".cliq/reviews/mr-12"), stored.directory)
        assertEquals(
            listOf("mr_12_meta.xml", "mr_12_project.xml", "mr_12_chunk_1.xml", "mr_12_chunk_2.xml"),
            stored.files.map { it.fileName.toString() },
        )
        assertEquals("<chunk index=\"2\"/>", Files.readString(stored.files.last()))
        assertEquals("*\n", Files.readString(project.resolve(".cliq/.gitignore")))
        assertEquals(".cliq/reviews/mr-12", ReviewContextStorage.relativeDirectory(ContextFileNames("mr", 12)))
    }

    @Test
    fun keepsPullAndMergeRequestsWithTheSameNumberApart() = withProject { project ->
        ReviewContextStorage.write(project, rendered("mr", 3, 1), gitIgnoreContextRoot = true)
        ReviewContextStorage.write(project, rendered("pr", 3, 1), gitIgnoreContextRoot = true)
        assertTrue(Files.exists(project.resolve(".cliq/reviews/mr-3/mr_3_meta.xml")))
        assertTrue(Files.exists(project.resolve(".cliq/reviews/pr-3/pr_3_meta.xml")))
    }

    @Test
    fun replacesStaleChunksAndKeepsForeignFiles() = withProject { project ->
        ReviewContextStorage.write(project, rendered("pr", 5, 3), gitIgnoreContextRoot = true)
        val foreign = project.resolve(".cliq/reviews/pr-5/notes.txt")
        Files.writeString(foreign, "mine")

        ReviewContextStorage.write(project, rendered("pr", 5, 1), gitIgnoreContextRoot = true)

        val names = Files.list(project.resolve(".cliq/reviews/pr-5")).use { stream -> stream.map { it.fileName.toString() }.toList() }.toSet()
        assertEquals(setOf("pr_5_meta.xml", "pr_5_project.xml", "pr_5_chunk_1.xml", "notes.txt"), names)
    }

    @Test
    fun removesOwnGitIgnoreWhenDisabled() = withProject { project ->
        ReviewContextStorage.write(project, rendered("mr", 1, 1), gitIgnoreContextRoot = true)
        ReviewContextStorage.write(project, rendered("mr", 1, 1), gitIgnoreContextRoot = false)
        assertFalse(Files.exists(project.resolve(".cliq/.gitignore")))
    }

    @Test
    fun rejectsUnexpectedPrefixes() = withProject { project ->
        assertFailsWith<IllegalArgumentException> {
            ReviewContextStorage.write(project, rendered("../x", 1, 1), gitIgnoreContextRoot = true)
        }
    }

    @Test
    fun refusesSymlinkedContextDirectory() = withProject { project ->
        val outside = Files.createTempDirectory("cliq_storage_outside")
        try {
            try {
                Files.createSymbolicLink(project.resolve(".cliq"), outside)
            } catch (unsupported: Exception) {
                return@withProject
            }
            assertFailsWith<IOException> { ReviewContextStorage.write(project, rendered("mr", 3, 1), gitIgnoreContextRoot = true) }
            assertTrue(Files.list(outside).use { it.count() } == 0L)
        } finally {
            outside.toFile().deleteRecursively()
        }
    }
}
