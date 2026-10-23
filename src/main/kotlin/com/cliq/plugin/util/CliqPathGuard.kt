package com.cliq.plugin.util

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.InvalidPathException
import java.nio.file.Path

object CliqPathGuard {

    fun projectRoots(project: Project): List<Path> {
        val rawRoots = ReadAction.compute<List<String>, RuntimeException> {
            if (project.isDisposed) return@compute emptyList()
            ProjectRootManager.getInstance(project).contentRoots.map { it.path }
        }
        val roots = rawRoots.mapNotNullTo(mutableListOf()) { canonicalize(it) }
        project.basePath?.let { base -> canonicalize(base)?.let(roots::add) }
        return roots.distinct()
    }

    fun isInsideProject(project: Project, candidate: String): Boolean =
        resolveInsideProject(project, candidate) != null

    fun resolveInsideProject(project: Project, candidate: String): Path? =
        resolveInsideRoots(projectRoots(project), candidate)

    fun resolveInsideRoots(roots: List<Path>, candidate: String): Path? {
        if (roots.isEmpty()) return null
        val resolved = canonicalize(candidate) ?: return null
        return resolved.takeIf { path -> roots.any { path.startsWith(it) } }
    }

    fun canonicalize(raw: String): Path? {
        val path = try {
            Path.of(raw)
        } catch (e: InvalidPathException) {
            return null
        }
        if (!path.isAbsolute) return null

        val normalized = path.normalize()
        var existing: Path = normalized
        val missingSegments = ArrayDeque<String>()

        while (!existing.toFile().exists()) {
            val name = existing.fileName ?: return null
            missingSegments.addFirst(name.toString())
            existing = existing.parent ?: return null
        }

        val realExisting = runCatching { existing.toRealPath() }.getOrElse { existing }
        return missingSegments.fold(realExisting) { acc, segment -> acc.resolve(segment) }.normalize()
    }
}
