package org.instagene.app.gui.project

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class ProjectFileWatcherTest {

    private fun tmpDir(): File = Files.createTempDirectory("watcher-test").toFile()

    @Test
    fun detectsNewFile() {
        val root = tmpDir()
        val latch = CountDownLatch(1)
        val watcher = ProjectFileWatcher { latch.countDown() }
        watcher.start(root)
        File(root, "new.fasta").writeText(">seq\nAAAA\n")
        assertTrue(latch.await(5, TimeUnit.SECONDS), "watcher must detect a new file")
        watcher.close()
    }

    @Test
    fun detectsDeletedFile() {
        val root = tmpDir()
        val seed = File(root, "to_delete.fasta").apply { writeText(">seq\nCCCC\n") }
        Thread.sleep(200)
        val latch = CountDownLatch(1)
        val watcher = ProjectFileWatcher { latch.countDown() }
        watcher.start(root)
        Thread.sleep(200)
        seed.delete()
        assertTrue(latch.await(5, TimeUnit.SECONDS), "watcher must detect a deleted file")
        watcher.close()
    }

    @Test
    fun detectsModifiedFile() {
        val root = tmpDir()
        val seed = File(root, "existing.fasta").apply { writeText(">seq\nAAAA\n") }
        Thread.sleep(200)
        val latch = CountDownLatch(1)
        val watcher = ProjectFileWatcher { latch.countDown() }
        watcher.start(root)
        Thread.sleep(200)
        seed.writeText(">seq\nAAAA\nTTTT\n")
        assertTrue(latch.await(5, TimeUnit.SECONDS), "watcher must detect a modified file")
        watcher.close()
    }

    @Test
    fun ignoresInstageneDir() {
        val root = tmpDir()
        val manifestDir = File(root, ".instagene").apply { mkdir() }
        Thread.sleep(200)
        var fired = false
        val watcher = ProjectFileWatcher { fired = true }
        watcher.start(root)
        Thread.sleep(200)
        File(manifestDir, "project.json").writeText("{}")
        Thread.sleep(1500)
        assertTrue(!fired, "events in .instagene/ must be ignored")
        watcher.close()
    }

    @Test
    fun detectsNestedNewFile() {
        val root = tmpDir()
        val sub = File(root, "subdir").apply { mkdir() }
        Thread.sleep(200)
        val latch = CountDownLatch(1)
        val watcher = ProjectFileWatcher { latch.countDown() }
        watcher.start(root)
        Thread.sleep(200)
        File(sub, "deep.fasta").writeText(">seq\nGGGG\n")
        assertTrue(latch.await(5, TimeUnit.SECONDS), "watcher must detect a nested new file")
        watcher.close()
    }

    @Test
    fun stopPreventsFurtherEvents() {
        val root = tmpDir()
        val latch = CountDownLatch(1)
        val watcher = ProjectFileWatcher { latch.countDown() }
        watcher.start(root)
        watcher.stop()
        File(root, "after_stop.fasta").writeText(">seq\nAAAA\n")
        assertTrue(!latch.await(2, TimeUnit.SECONDS), "no events should fire after stop")
    }
}
