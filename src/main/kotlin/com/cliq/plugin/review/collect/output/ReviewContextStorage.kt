package com.cliq.plugin.review.collect.output

import com.cliq.plugin.review.collect.render.ContextFileNames
import com.cliq.plugin.review.collect.render.RenderedContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions

class StoredContext(
    val directory: Path,
    val files: List<Path>,
)

object ReviewContextStorage {

    const val CONTEXT_ROOT = ".cliq"
    const val REVIEWS_ROOT = "reviews"
    private const val GIT_IGNORE = ".gitignore"
    private const val GIT_IGNORE_CONTENT = "*\n"
    private const val OWNER_ONLY_DIRECTORY = "rwx------"
    private const val OWNER_ONLY_FILE = "rw-------"
    private val CONTEXT_FILE_NAME = Regex("^[a-z]{2}_\\d+_(meta|project|chunk_\\d+)\\.xml$")
    private val PREFIX = Regex("^[a-z]{2}$")

    private val posix: Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    fun relativeDirectory(names: ContextFileNames): String = "$CONTEXT_ROOT/$REVIEWS_ROOT/${directoryName(names)}"

    fun write(projectBase: Path, rendered: RenderedContext, gitIgnoreContextRoot: Boolean): StoredContext {
        val base = projectBase.toRealPath()
        val root = ensureDirectory(base, base.resolve(CONTEXT_ROOT))
        syncGitIgnore(root, gitIgnoreContextRoot)
        val reviews = ensureDirectory(base, root.resolve(REVIEWS_ROOT))
        val target = ensureDirectory(base, reviews.resolve(directoryName(rendered.names)))
        removePreviousContext(target)

        val files = buildList {
            add(writeFile(target, rendered.names.meta, rendered.meta))
            add(writeFile(target, rendered.names.project, rendered.project))
            rendered.chunks.forEach { add(writeFile(target, it.fileName, it.xml)) }
        }
        return StoredContext(target, files)
    }

    private fun directoryName(names: ContextFileNames): String {
        require(PREFIX.matches(names.prefix)) { "Unexpected context prefix: ${names.prefix}" }
        return "${names.prefix}-${names.number}"
    }

    private fun ensureDirectory(base: Path, directory: Path): Path {
        if (Files.isSymbolicLink(directory)) throw IOException("Refusing to write Cliq context through a symbolic link: $directory")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (posix) {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY_DIRECTORY)))
            } else {
                Files.createDirectory(directory)
            }
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw IOException("Not a directory: $directory")
        val real = directory.toRealPath()
        if (!real.startsWith(base)) throw IOException("Cliq context directory escapes the project: $directory")
        return real
    }

    private fun syncGitIgnore(root: Path, enabled: Boolean) {
        val file = root.resolve(GIT_IGNORE)
        if (Files.isSymbolicLink(file)) throw IOException("Refusing to touch a symbolic link: $file")
        val exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS)
        when {
            enabled && !exists -> writeNew(file, GIT_IGNORE_CONTENT)
            !enabled && exists && Files.readString(file) == GIT_IGNORE_CONTENT -> Files.delete(file)
        }
    }

    private fun removePreviousContext(directory: Path) {
        Files.list(directory).use { entries ->
            entries
                .filter { CONTEXT_FILE_NAME.matches(it.fileName.toString()) }
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private fun writeFile(directory: Path, name: String, content: String): Path {
        require(CONTEXT_FILE_NAME.matches(name)) { "Unexpected context file name: $name" }
        val file = directory.resolve(name)
        writeNew(file, content)
        return file
    }

    private fun writeNew(file: Path, content: String) {
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val attributes: Array<FileAttribute<*>> = if (posix) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY_FILE)))
        } else {
            emptyArray()
        }
        Files.newByteChannel(file, options, *attributes).use { channel ->
            val buffer = ByteBuffer.wrap(content.toByteArray(StandardCharsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }
}
