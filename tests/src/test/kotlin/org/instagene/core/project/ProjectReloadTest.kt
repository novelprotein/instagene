package org.instagene.core.project

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ProjectReloadTest {

    @Test
    fun cleanChangedFilesReloadButDirtyFilesArePreserved() {
        val file = Files.createTempFile("instagene-reload", ".fasta").toFile()
        try {
            file.writeText(">x\nAAAA\n")
            val before = assertNotNull(ProjectReload.snapshot(file))
            file.writeText(">x\nCCCC\n")
            file.setLastModified(before.modifiedMillis + 2_000)
            val after = assertNotNull(ProjectReload.snapshot(file))

            assertEquals(
                ProjectReloadDisposition.RELOAD_FROM_DISK,
                ProjectReload.decide(before, after, dirty = false).disposition,
            )
            assertEquals(
                ProjectReloadDisposition.PRESERVE_LOCAL_CONFLICT,
                ProjectReload.decide(before, after, dirty = true).disposition,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingFilesRetainTabsAndDistinguishUnsavedWork() {
        val file = File.createTempFile("instagene-reload", ".fasta")
        try {
            val before = assertNotNull(ProjectReload.snapshot(file))
            file.delete()
            assertEquals(
                ProjectReloadDisposition.MISSING_ON_DISK,
                ProjectReload.decide(before, ProjectReload.snapshot(file), dirty = false).disposition,
            )
            assertEquals(
                ProjectReloadDisposition.PRESERVE_MISSING_LOCAL,
                ProjectReload.decide(before, ProjectReload.snapshot(file), dirty = true).disposition,
            )
        } finally {
            file.delete()
        }
    }
}
