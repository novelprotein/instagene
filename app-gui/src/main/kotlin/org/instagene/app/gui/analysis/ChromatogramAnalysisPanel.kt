package org.instagene.app.gui.analysis

import org.instagene.core.ChromatogramReader
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JScrollPane
import javax.swing.JTextField

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
        fileField.text = file.absolutePath
        runCatching {
            val bytes = file.readBytes()
            when {
                ChromatogramReader.looksLikeAbi(bytes) -> ChromatogramReader.readAbi(bytes, file.name)
                ChromatogramReader.looksLikeScf(bytes) -> ChromatogramReader.readScf(bytes, file.name)
                else -> error("Unrecognized chromatogram format in '${file.name}'. Expected ABI (.ab1) or SCF (.scf).")
            }
        }.onSuccess { record ->
            output.text = buildString {
                append("${record.name}: ${record.bases.length} called bases\n\n")
                record.bases.forEachIndexed { index, base ->
                    val quality = record.qualities.getOrNull(index) ?: 0
                    append("%6d  %c  quality=%3d  %s\n".format(index + 1, base, quality, "#".repeat((quality / 5).coerceAtMost(20))))
                }
            }
        }.onFailure { output.text = it.message ?: "Unable to read chromatogram" }
    }
}
