package com.cliq.plugin.review.collect.related

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper

interface RelatedFileIndex {

    suspend fun filesNamed(names: Collection<String>): List<String>

    suspend fun filesMentioning(word: String, limit: Int): List<String>
}

class IdeRelatedFileIndex(
    private val project: Project,
    repositoryRootPath: String,
) : RelatedFileIndex {

    private val rootPrefix = repositoryRootPath.trimEnd('/') + "/"

    override suspend fun filesNamed(names: Collection<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        return readAction {
            if (!isIndexReady()) return@readAction emptyList()
            try {
                val scope = GlobalSearchScope.projectScope(project)
                names.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
                    .mapNotNull { toRepositoryPath(it.path) }
                    .distinct()
            } catch (e: IndexNotReadyException) {
                emptyList()
            }
        }
    }

    override suspend fun filesMentioning(word: String, limit: Int): List<String> =
        readAction {
            if (!isIndexReady()) return@readAction emptyList()
            val found = LinkedHashSet<String>()
            try {
                PsiSearchHelper.getInstance(project).processAllFilesWithWord(
                    word,
                    GlobalSearchScope.projectScope(project),
                    { psiFile ->
                        psiFile.virtualFile?.path?.let(::toRepositoryPath)?.let(found::add)
                        found.size < limit
                    },
                    true,
                )
            } catch (e: IndexNotReadyException) {
                return@readAction emptyList()
            }
            found.toList()
        }

    private fun isIndexReady(): Boolean = !project.isDisposed && !DumbService.isDumb(project)

    private fun toRepositoryPath(path: String): String? =
        path.takeIf { it.startsWith(rootPrefix) }?.removePrefix(rootPrefix)
}
