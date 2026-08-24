package org.instagene.app.gui.analysis

import org.instagene.app.gui.file.FileOpenService
import org.instagene.app.gui.file.OpenedFile
import org.instagene.core.ChromatogramRecord
import java.awt.BorderLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.SwingUtilities

internal class ChromatogramAnalysisPanel : BoundAnalysisPanel() {
    private val fileField = JTextField(36)
    private val output = output()

    init {
        val choose = JButton("Open chromatogram...")
        choose.addActionListener { open() }
        add(row(choose, fileField), BorderLayout.NORTH)
        add(JScrollPane(output), BorderLayout.CENTER)
    }

    private fun open() {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        output.text = "Opening ${file.name}…"
        Thread {
            runCatching { FileOpenService.load(file) }
                .onSuccess { opened ->
                    SwingUtilities.invokeLater {
                        (opened as? OpenedFile.Chromatogram)?.let { showChromatogram(it.record, file) }
                            ?: run { output.text = "'${file.name}' is not a chromatogram." }
                    }
                }
                .onFailure { error -> SwingUtilities.invokeLater { output.text = error.message ?: "Unable to read chromatogram" } }
        }.apply { isDaemon = true; name = "ChromatogramReader-${file.name}" }.start()
    }

    /** Shows a trace supplied by the unified file-open flow without rereading it on the EDT. */
    internal fun showChromatogram(record: ChromatogramRecord, sourceFile: File? = null) {
        fileField.text = sourceFile?.absolutePath ?: record.source
        output.text = buildString {
            val threshold = 20
            append("${record.name}: ${record.bases.length} called bases\n")
            append("Quality overlay: ! marks bases below Q$threshold\n\n")
            record.bases.forEachIndexed { index, base ->
                val quality = record.qualities.getOrNull(index) ?: 0
                val marker = if (quality < threshold) "!" else " "
                append("%6d  %c  quality=%3d  %s %s\n".format(index + 1, base, quality, marker, "#".repeat((quality / 5).coerceAtMost(20))))
            }
        }
    }
}
