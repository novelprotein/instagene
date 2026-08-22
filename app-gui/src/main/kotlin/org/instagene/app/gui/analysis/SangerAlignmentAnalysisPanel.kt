package org.instagene.app.gui.analysis

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.installRowContextMenu
import org.instagene.core.ChromatogramReader
import org.instagene.core.SangerAlignment
import org.instagene.core.SangerOptions
import java.awt.BorderLayout
import java.io.File
import javax.swing.*
import javax.swing.table.DefaultTableModel

internal class SangerAlignmentAnalysisPanel : BoundAnalysisPanel() {
    private val queryFiles = JTextField(30)
    private val minQuality = JSpinner(SpinnerNumberModel(20, 0, 99, 1))
    private val model = DefaultTableModel(arrayOf("Read name", "Identity", "Mismatches", "Aligned length", "Confidence"), 0)
    private val table = JTable(model)
    private val output = output()
    private var lastResult: org.instagene.core.SangerAlignmentResult? = null

    init {
        val choose = JButton("Choose trace files...")
        choose.toolTipText = "Select ABI/SCF chromatogram files to align against the current sequence."
        choose.addActionListener {
            val chooser = JFileChooser().apply { isMultiSelectionEnabled = true }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                // Keep absolute paths in the executable value; displaying only
                // names made a chooser selection fail unless the working
                // directory happened to contain the trace files.
                queryFiles.text = chooser.selectedFiles.joinToString(", ") { it.absolutePath }
                queryFiles.toolTipText = chooser.selectedFiles.joinToString("\n") { it.absolutePath }
            }
        }
        val run = JButton("Align reads")
        run.toolTipText = "Align the selected chromatogram reads to the current reference sequence."
        run.addActionListener { execute() }
        val saveReport = JButton("Save report")
        saveReport.toolTipText = "Export the verification summary as Markdown or JSON."
        saveReport.addActionListener { saveReport() }
        add(row(choose, queryFiles, JLabel("Min quality"), minQuality, run, saveReport), BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(JScrollPane(output).apply { preferredSize = java.awt.Dimension(10, 60) }, BorderLayout.SOUTH)
        table.installRowContextMenu { row -> sangerPopup(row) }
        table.selectionModel.addListSelectionListener {
            val row = table.selectedRow
            val result = lastResult
            if (row >= 0 && result != null && row < result.reads.size) {
                val read = result.reads[row]
                output.text = buildString {
                    append("${read.readName}: ${read.alignedLength} aligned bases\n")
                    append("Identity: ${"%.2f".format(read.identity * 100)}%  Confidence: ${read.confidence()}\n")
                    append("Low-quality bases: ${read.lowQualityBases}\n\n")
                    if (read.mismatches.isEmpty()) append("No mismatches.")
                    else read.mismatches.forEach { mismatch ->
                        append("Reference ${mismatch.refPos + 1}, read ${mismatch.readPos + 1}: ")
                        append("${mismatch.refBase} -> ${mismatch.readBase} (${mismatch.kind})\n")
                    }
                }
            }
        }
    }

    private fun execute() {
        val paths = queryFiles.text.split(',').map(String::trim).filter(String::isNotEmpty)
        if (paths.isEmpty()) { output.text = "Choose one or more chromatogram files."; return }
        runCatching {
            val reads = paths.map { path ->
                val file = File(path)
                val bytes = file.readBytes()
                when {
                    ChromatogramReader.looksLikeAbi(bytes) -> ChromatogramReader.readAbi(bytes, file.name)
                    ChromatogramReader.looksLikeScf(bytes) -> ChromatogramReader.readScf(bytes, file.name)
                    else -> error("Unrecognized format: ${file.name}")
                }
            }
            val result = SangerAlignment.alignChromatograms(
                doc.seq,
                reads,
                SangerOptions(minQuality = (minQuality.value as Number).toInt(), trimQuality = (minQuality.value as Number).toInt()),
            )
            lastResult = result
            model.rowCount = 0
            result.reads.forEach { r ->
                model.addRow(arrayOf<Any?>(
                    r.readName, "%.2f%%".format(r.identity * 100), r.mismatches.size, r.alignedLength,
                    r.confidence().name,
                ))
            }
            output.text = buildString {
                append("Aligned ${result.summary.totalReads} read(s)\n")
                append("Average identity: ${"%.2f".format(result.summary.averageIdentity * 100)}%\n")
                val allMismatches = result.reads.flatMap { it.mismatches }
                if (allMismatches.isNotEmpty()) {
                    append("\nMismatch details:\n")
                    allMismatches.groupBy { it.refPos }.forEach { (pos, mm) ->
                        append("  Position ${pos + 1}: ${mm.first().refBase} -> ${mm.first().readBase} (${mm.size} read(s))\n")
                    }
                }
            }
        }.onFailure { output.text = it.message ?: "Sanger alignment failed" }
    }

    private fun sangerPopup(row: Int?): JPopupMenu = JPopupMenu().apply {
        val hasRow = row != null && row in 0 until model.rowCount
        add(ContextMenus.item("Copy row data", "Copy the alignment result row to the clipboard.", hasRow) {
            copyRowToClipboard(model, row)
        })
    }

    private fun saveReport() {
        val result = lastResult ?: run {
            output.text = "Run an alignment before exporting its report."
            return
        }
        val chooser = JFileChooser().apply { dialogTitle = "Save Sanger verification report" }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        runCatching {
            val report = org.instagene.core.Reports.verificationReport(doc.seq, result)
            if (file.extension.equals("json", true)) file.writeText(org.instagene.core.Reports.verificationJson(report))
            else file.writeText(org.instagene.core.Reports.verificationMarkdown(report))
        }.onFailure { output.text = it.message ?: "Unable to save verification report" }
    }
}
