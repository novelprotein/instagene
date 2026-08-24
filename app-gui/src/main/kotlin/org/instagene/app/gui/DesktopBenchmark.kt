package org.instagene.app.gui

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.SequenceView
import org.instagene.core.PerformanceTargets
import org.instagene.core.Seq
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.system.measureNanoTime

/**
 * Headless measurements of the desktop paths that researchers actually see.
 *
 * The output deliberately matches the CLI benchmark's section/row grammar so
 * the existing CI parser and GitHub Pages dashboard can trend these values
 * alongside engine work. It measures the first and a scrolled viewport, not a
 * full-sequence paint: [SequenceView] is intentionally viewport-virtualized.
 */
object DesktopBenchmark {
    private fun out(value: Any? = "") {
        print(value)
        print('\n')
    }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val plasmid = syntheticDna(PerformanceTargets.PLASMID_BASES)
        val construct = syntheticDna(PerformanceTargets.CONSTRUCT_BASES)
        out("--- Desktop ---")
        bench("SequenceView first viewport (10 kb)") {
            renderViewport(plasmid)
        }
        bench("SequenceView first viewport (100 kb)") {
            renderViewport(construct)
        }
        bench("SequenceView scrolled viewport (100 kb)") {
            renderViewport(construct, scrollRows = 500)
        }
        out()
    }

    private fun bench(label: String, block: () -> Unit) {
        val repeats = 3
        var total = 0L
        repeat(repeats) { total += measureNanoTime(block) }
        val millis = total / repeats / 1_000_000.0
        out("  %-40s %8.1f ms".format(label, millis))
    }

    private fun renderViewport(sequence: String, scrollRows: Int = 0) {
        SwingUtilities.invokeAndWait {
            val view = SequenceView(SeqDocument(Seq(name = "benchmark", bases = sequence)))
            try {
                val width = 1_000
                val height = 480
                view.setSize(width, height)
                view.doLayout()
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    if (scrollRows > 0) {
                        // Translate the graphics so the off-screen image still
                        // represents a normal-sized viewport at a later row.
                        val scrollY = scrollRows * 60
                        graphics.translate(0, -scrollY)
                        graphics.clipRect(0, scrollY, width, height)
                    }
                    view.paint(graphics)
                } finally {
                    graphics.dispose()
                }
            } finally {
                view.dispose()
            }
        }
    }

    private fun syntheticDna(bases: Int): String = "ACGT".repeat((bases + 3) / 4).take(bases)
}
