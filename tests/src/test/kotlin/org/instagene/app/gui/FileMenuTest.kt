package org.instagene.app.gui

import org.instagene.core.Seq
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * File I/O for the desktop app: loading and saving must never block the EDT,
 * and must handle large files. Runs headless, exercising the exact same
 * background-thread path the File menu uses.
 */
class FileMenuTest {

    /** Writes a FASTA with [bases] of random-ish, deterministic nucleotide data. */
    private fun largeFasta(name: String, bases: Int): File {
        val alphabet = "ACGT"
        val sb = StringBuilder()
        // Deterministic pseudo-random so the test is reproducible.
        var x = 123456789
        repeat(bases) {
            x = x * 1664525 + 1013904223 and 0xFFFFFFFF.toInt()
            sb.append(alphabet[Math.floorMod(x, 4)])
        }
        val body = sb.toString().chunked(60).joinToString("\n")
        val file = Files.createTempFile(name, ".fasta").toFile()
        file.writeText(">${name}_large\n$body\n")
        file.deleteOnExit()
        return file
    }

    /** Pumps the EDT until [condition] holds, or [timeoutMs] elapses. */
    private fun awaitEdt(timeoutMs: Long = 60_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (SwingUtilities.isEventDispatchThread()) {
                if (condition()) return true
                Thread.sleep(10)
            } else {
                // Flush queued events (the background thread's invokeLater runs here).
                SwingUtilities.invokeAndWait { }
                if (condition()) return true
                Thread.sleep(10)
            }
        }
        return condition()
    }

    /** Runs [block] on the EDT, rethrowing any exception it raises. */
    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        var error: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
        return result ?: throw IllegalStateException("onEdt produced null")
    }

    @Test
    fun loadFromFileLoadsALargeFastaAsynchronously() {
        val expected = 5_000_000
        val file = largeFasta("big", expected)
        val doc = SeqDocument(Seq(bases = ""))
        val menu = FileMenu(null, doc)

        menu.loadFromFile(file)

        // The load runs on a worker thread and lands on the EDT; poll for it.
        assertTrue(
            awaitEdt { doc.file == file && doc.seq.length == expected },
            "Large file was not loaded within the timeout (length=${doc.seq.length})",
        )
        assertEquals(expected, doc.seq.length)
        assertEquals(file, doc.file)
        assertFalse(doc.isDirty)
        assertEquals("big_large", doc.seq.name) // the FASTA header, not the filename
    }

    @Test
    fun loadFromFileDoesNotBlockTheCallingThread() {
        val expected = 2_000_000
        val file = largeFasta("quick", expected)
        val doc = SeqDocument(Seq(bases = ""))
        val menu = FileMenu(null, doc)

        val start = System.currentTimeMillis()
        menu.loadFromFile(file)
        // Returns immediately: parsing happens on a background thread.
        assertTrue(System.currentTimeMillis() - start < 2_000, "loadFromFile blocked the caller")

        assertTrue(awaitEdt { doc.seq.length == expected })
    }

    /**
     * The genome-labelling scenario: a 70 Mbp FASTA opened through the full panel
     * stack. Every panel listens for the SEQUENCE notification, so this is where a
     * whole-genome scan on the EDT used to wedge the app: the Digest panel
     * recomputed cut counts for all 49 enzymes over the full sequence on the
     * event thread, freezing the window for minutes.
     */
    @Test
    fun hugeGenomeLoadsThroughFullPanelStackWithoutFreezingTheEdt() {
        val expected = 70_000_000
        val file = largeFasta("genome", expected)
        val content = onEdt { InstaGeneContent(null) }

        val start = System.currentTimeMillis()
        FileMenu(null, content.doc).loadFromFile(file)

        assertTrue(
            awaitEdt(180_000) { content.doc.file == file && content.doc.seq.length == expected },
            "70 Mbp genome was not loaded within the timeout (length=${content.doc.seq.length})",
        )
        assertFalse(content.doc.isDirty)
        // The parse streams and the per-panel refreshes are cheap, so the load
        // lands in seconds rather than minutes.
        assertTrue(
            System.currentTimeMillis() - start < 60_000,
            "Loading a 70 Mbp genome took too long to apply on the EDT",
        )
    }

    /**
     * The Digest panel's per-enzyme cut counts must land on a background thread:
     * the Cuts column fills in after the sequence appears, never blocking it.
     */
    @Test
    fun digestCutCountsArriveAsynchronouslyForACrowdedGenome() {
        val expected = 2_000_000
        val file = largeFasta("digest", expected)
        val content = onEdt { InstaGeneContent(null) }

        FileMenu(null, content.doc).loadFromFile(file)
        assertTrue(awaitEdt { content.doc.seq.length == expected })

        assertTrue(
            awaitEdt(120_000) { content.digestPanel.computedCutCounts()?.values?.any { it > 0 } == true },
            "Async cut counts never arrived for the 2 Mbp genome",
        )
    }
}
