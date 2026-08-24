package org.instagene.app.gui

import org.instagene.app.gui.menu.FileMenu
import org.instagene.core.Seq
import org.instagene.core.io.SeqIO
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for a failure to open large genome files.
 *
 * A multi-contig genome FASTA whose total size exceeds the JVM heap must still
 * load, because only the first contig is ever buffered. The file is written to
 * be larger than 2 GB so the old `file.length().toInt()` capacity hint would
 * have overflowed. It also exceeds a modest heap, so buffering every contig
 * would run out of memory. Run with `-Dinstagene.heap=3g` (see build.gradle.kts).
 */
class LargeGenomeLoadTest {

    private val workerErrors = ArrayList<Throwable>()
    private val edtErrors = ArrayList<Throwable>()

    private fun installTrap() {
        workerErrors.clear()
        edtErrors.clear()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(workerErrors) { workerErrors += e } }
        SwingUtilities.invokeAndWait {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> synchronized(edtErrors) { edtErrors += e } }
        }
    }

    /** Writes a FASTA containing [contigs] records of [basesPerContig] nucleotides each. */
    private fun fasta(contigs: Int, basesPerContig: Int): File {
        val file = Files.createTempFile("genome", ".fna").toFile()
        file.deleteOnExit()
        val alphabet = "ACGT"
        var x = 123456789
        BufferedWriter(FileWriter(file), 1 shl 20).use { w ->
            for (c in 0 until contigs) {
                w.write(">chr${c + 1} synthetic contig ${c + 1}\n")
                val line = CharArray(60)
                var col = 0
                repeat(basesPerContig) {
                    x = x * 1664525 + 1013904223 and 0xFFFFFFFF.toInt()
                    line[col++] = alphabet[Math.floorMod(x, 4)]
                    if (col == 60) {
                        w.write(line)
                        w.newLine()
                        col = 0
                    }
                }
                if (col > 0) {
                    w.write(line, 0, col)
                    w.newLine()
                }
            }
        }
        return file
    }

    @Test
    fun multiContigGenomeLargerThanHeapLoadsFirstContig() {
        installTrap()
        val contigs = 22
        val basesPerContig = 130_000_000
        val totalBases = contigs.toLong() * basesPerContig
        println("generating $contigs x $basesPerContig bp FASTA (${totalBases} bp total)...")
        val file = fasta(contigs, basesPerContig)
        var content: InstaGeneContent? = null
        try {
            println("generated ${file.length() / (1024.0 * 1024.0)} MB FASTA")
            assertTrue(file.length() > 2L * 1024 * 1024 * 1024, "file should exceed 2 GB to hit the old Int-overflow path")

            var engineSeq: Seq? = null
            val readMs = kotlin.system.measureTimeMillis {
                try {
                    engineSeq = SeqIO.read(file)
                } catch (t: Throwable) {
                    println("engine read FAILED: ${t.javaClass.simpleName}: ${t.message}")
                    t.printStackTrace()
                }
            }
            println("engine read: ${engineSeq?.length} bp, name=${engineSeq?.name} (${readMs}ms)")
            assertEquals(basesPerContig, engineSeq?.length, "engine should load only the first contig")
            assertEquals("chr1", engineSeq?.name)

            SwingUtilities.invokeAndWait { content = InstaGeneContent(null) }
            val loadedContent = requireNotNull(content)
            val menu = FileMenu(null, loadedContent.doc)

            val loadStart = System.currentTimeMillis()
            menu.loadFromFile(file)
            var landed = false
            while (System.currentTimeMillis() - loadStart < 120_000) {
                SwingUtilities.invokeAndWait { }
                Thread.sleep(50)
                if (loadedContent.doc.seq.length == basesPerContig) {
                    landed = true
                    break
                }
            }
            val loadMs = System.currentTimeMillis() - loadStart
            println("GUI load landed=$landed in ${loadMs}ms, doc.length=${loadedContent.doc.seq.length}")
            assertTrue(landed, "first contig never landed in the document")
            assertEquals(basesPerContig, loadedContent.doc.seq.length)
            assertEquals("chr1", loadedContent.doc.seq.name)

            synchronized(workerErrors) {
                assertTrue(
                    workerErrors.isEmpty(),
                    "worker thread errors: ${workerErrors.map { it.javaClass.simpleName + ": " + it.message }}"
                )
            }
            synchronized(edtErrors) {
                assertTrue(
                    edtErrors.isEmpty(),
                    "EDT errors: ${edtErrors.map { it.javaClass.simpleName + ": " + it.message }}"
                )
            }
        } finally {
            content?.let { opened ->
                if (SwingUtilities.isEventDispatchThread()) opened.dispose()
                else SwingUtilities.invokeAndWait { opened.dispose() }
            }
            file.delete()
        }
    }
}
