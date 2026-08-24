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
    private companion object {
        const val CONTIGS = 22
        const val BASES_PER_CONTIG = 130_000_000
    }

    private fun out(value: Any? = "") {
        print(value)
        print('\n')
    }

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

    /** Writes a FASTA containing [CONTIGS] records of [BASES_PER_CONTIG] nucleotides each. */
    private fun fasta(): File {
        val file = Files.createTempFile("genome", ".fna").toFile()
        file.deleteOnExit()
        val alphabet = "ACGT"
        var x = 123456789
        BufferedWriter(FileWriter(file), 1 shl 20).use { w ->
            for (c in 0 until CONTIGS) {
                w.write(">chr${c + 1} synthetic contig ${c + 1}\n")
                val line = CharArray(60)
                var col = 0
                repeat(BASES_PER_CONTIG) {
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
        val totalBases = CONTIGS.toLong() * BASES_PER_CONTIG
        out("generating $CONTIGS x $BASES_PER_CONTIG bp FASTA (${totalBases} bp total)...")
        val file = fasta()
        var content: InstaGeneContent? = null
        try {
            out("generated ${file.length() / (1024.0 * 1024.0)} MB FASTA")
            assertTrue(file.length() > 2L * 1024 * 1024 * 1024, "file should exceed 2 GB to hit the old Int-overflow path")

            var engineSeq: Seq? = null
            val readMs = kotlin.system.measureTimeMillis {
                try {
                    engineSeq = SeqIO.read(file)
                } catch (t: Throwable) {
                    out("engine read FAILED: ${t.javaClass.simpleName}: ${t.message}")
                    t.printStackTrace()
                }
            }
            out("engine read: ${engineSeq?.length} bp, name=${engineSeq?.name} (${readMs}ms)")
            assertEquals(BASES_PER_CONTIG, engineSeq?.length, "engine should load only the first contig")
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
                if (loadedContent.doc.seq.length == BASES_PER_CONTIG) {
                    landed = true
                    break
                }
            }
            val loadMs = System.currentTimeMillis() - loadStart
            out("GUI load landed=$landed in ${loadMs}ms, doc.length=${loadedContent.doc.seq.length}")
            assertTrue(landed, "first contig never landed in the document")
            assertEquals(BASES_PER_CONTIG, loadedContent.doc.seq.length)
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
