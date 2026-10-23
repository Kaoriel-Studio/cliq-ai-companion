package com.cliq.plugin.diff

import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class CliqDiffManagerTest : BasePlatformTestCase() {

    fun testValidateTargetRejectsPathOutsideProject() {
        val manager = project.service<CliqDiffManager>()
        val outside = File(System.getProperty("java.io.tmpdir"), "cliq_outside_${System.currentTimeMillis()}.txt")

        val rejection = manager.validateTarget(outside.absolutePath)

        assertNotNull("Writing outside the project must be refused", rejection)
        assertTrue(rejection!!.contains("outside the project") || rejection.contains("not trusted"))
    }

    fun testValidateTargetRejectsParentTraversal() {
        val manager = project.service<CliqDiffManager>()
        val basePath = project.basePath ?: return

        val rejection = manager.validateTarget("$basePath/../escaped.txt")

        assertNotNull("Parent traversal outside the project must be refused", rejection)
    }

    fun testApplyDirectlyDoesNotWriteOutsideProject() {
        val manager = project.service<CliqDiffManager>()
        val outside = File(System.getProperty("java.io.tmpdir"), "cliq_blocked_${System.currentTimeMillis()}.txt")

        try {
            manager.applyDirectly(outside.absolutePath, "should never be written")
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            assertFalse("Cliq must not create files outside the project", outside.exists())
        } finally {
            outside.delete()
        }
    }

    fun testNoPendingReviewsInitially() {
        assertEmpty(project.service<CliqDiffManager>().pendingFilePaths())
    }
}
