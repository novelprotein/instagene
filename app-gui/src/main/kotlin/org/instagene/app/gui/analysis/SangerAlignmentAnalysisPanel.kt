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
import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartPanel
import org.jfree.chart.plot.PlotOrientation
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection

internal class SangerAlignmentAnalysisPanel : BoundAnalysisPanel() {
    private val queryFiles = JTextField(30)
    private val minQuality = JSpinner(SpinnerNumberModel(20, 0, 99, 1))
    private val model = DefaultTableModel(arrayOf("Read name", "Identity", "Mismatches", "Aligned length", "Confidence"), 0)
    private val table = JTable(model)
    private val output = output()
    private val traceDataset = XYSeriesCollection()
    private val traceChart = ChartFactory.createXYLineChart(
        "Chromatogram trace", "Trace sample", "Signal", traceDataset,
        PlotOrientation.VERTICAL, true, false, false,
    )
    private val tracePanel = ChartPanel(traceChart).apply {
        preferredSize = java.awt.Dimension(10, 200)
        isMouseWheelEnabled = true
    }
    private var lastResult: org.instagene.core.SangerAlignmentResult? = null
    private var lastChromatograms: List<org.instagene.core.ChromatogramRecord> = emptyList()

    init {
        val choose = JButton("Choose trace files...")
        choose.toolTipText = "Select ABI/SCF chromatogram files to align against the current sequence."
        choose.addActionListener {
            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = true
                fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                // Keep absolute paths in the executable value; displaying only
                // names made a chooser selection fail unless the working
                // directory happened to contain the trace files.
                val selected = (chooser.selectedFiles.toList() + listOfNotNull(chooser.selectedFile))
                    .distinct()
                    .flatMap(::traceFiles)
                    .distinct()
                queryFiles.text = selected.joinToString(", ") { it.absolutePath }
                queryFiles.toolTipText = selected.joinToString("\n") { it.absolutePath }
            }
        }
        val run = JButton("Align reads")
        run.toolTipText = "Align the selected chromatogram reads to the current reference sequence."
        run.addActionListener { execute() }
        val saveReport = JButton("Save report")
        saveReport.toolTipText = "Export the verification summary as Markdown or JSON."
        saveReport.addActionListener { saveReport() }
        add(row(choose, queryFiles, JLabel("Min quality"), minQuality, run, saveReport), BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), tracePanel).apply {
            resizeWeight = 0.55
        }, BorderLayout.CENTER)
        add(JScrollPane(output).apply { preferredSize = java.awt.Dimension(10, 60) }, BorderLayout.SOUTH)
        table.installRowContextMenu { row -> sangerPopup(row) }
        table.selectionModel.addListSelectionListener {
            val row = table.selectedRow
            val result = lastResult
            if (row >= 0 && result != null && row < result.reads.size) {
                val read = result.reads[row]
                val chromatogram = lastChromatograms.getOrNull(row)
                showTrace(chromatogram, read)
                output.text = buildString {
                    append("${read.readName}: ${read.alignedLength} aligned bases\n")
                    append("Identity: ${"%.2f".format(read.identity * 100)}%  Confidence: ${read.confidence()}\n")
                    append("Low-quality bases: ${read.lowQualityBases}\n\n")
                    if (read.mismatches.isEmpty()) append("No mismatches.")
                    else read.mismatches.forEach { mismatch ->
                        append("Reference ${mismatch.refPos + 1}, read ${mismatch.readPos + 1}: ")
                        append("${mismatch.refBase} -> ${mismatch.readBase} (${mismatch.kind})\n")
                    }
                    if (chromatogram != null && read.mismatches.isNotEmpty()) {
                        val center = read.mismatches.first().readPos
                        val start = (center - 8).coerceAtLeast(0)
                        val end = (center + 9).coerceAtMost(chromatogram.bases.length)
                        append("\nQuality context (${start + 1}..$end):\n")
                        append(chromatogram.bases.substring(start, end)).append('\n')
                        append(chromatogram.qualities.drop(start).take(end - start).joinToString(" "))
                        if (chromatogram.trace?.hasSignal() != true) {
                            append("\n\nRaw signal channels are unavailable in this trace file.")
                        }
                    }
                }
            }
        }
    }

    private fun execute() {
        val paths = queryFiles.text.split(',').map(String::trim).filter(String::isNotEmpty)
        if (paths.isEmpty()) { output.text = "Choose one or more chromatogram files."; return }
        runCatching {
            val failures = mutableListOf<String>()
            val reads = paths.mapNotNull { path ->
                runCatching {
                    val file = File(path)
                    val bytes = file.readBytes()
                    when {
                        ChromatogramReader.looksLikeAbi(bytes) -> ChromatogramReader.readAbi(bytes, file.name)
                        ChromatogramReader.looksLikeScf(bytes) -> ChromatogramReader.readScf(bytes, file.name)
                        else -> error("Unrecognized format: ${file.name}")
                    }
                }.onFailure { error -> failures += "${File(path).name}: ${error.message ?: "unable to read chromatogram"}" }.getOrNull()
            }
            if (reads.isEmpty()) error("No readable ABI/SCF chromatograms were selected.")
            lastChromatograms = reads
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
                if (result.summary.uncoveredReferenceBases > 0) {
                    append("Uncovered reference bases: ${result.summary.uncoveredReferenceBases}\n")
                }
                if (failures.isNotEmpty()) {
                    append("\nSkipped ${failures.size} unreadable file(s):\n")
                    failures.forEach { append("  - $it\n") }
                }
            }
        }.onFailure { output.text = it.message ?: "Sanger alignment failed" }
    }

    private fun traceFiles(file: File): List<File> = when {
        file.isDirectory -> file.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("ab1", "abi", "scf") }.toList()
        file.isFile && file.extension.lowercase() in setOf("ab1", "abi", "scf") -> listOf(file)
        else -> emptyList()
    }

    private fun showTrace(chromatogram: org.instagene.core.ChromatogramRecord?, read: org.instagene.core.AlignedRead) {
        traceDataset.removeAllSeries()
        val trace = chromatogram?.trace
        val mismatch = read.mismatches.firstOrNull()
        if (trace == null || mismatch == null || !trace.hasSignal()) {
            traceChart.title.text = "Chromatogram trace (select a read with a trace-backed mismatch)"
            return
        }
        val peak = trace.peakPositions.getOrNull(mismatch.readPos)
        if (peak == null) {
            traceChart.title.text = "Chromatogram trace (no peak location for the selected mismatch)"
            return
        }
        val start = (peak - 300).coerceAtLeast(0)
        val end = (peak + 300).coerceAtMost(trace.channels.values.maxOf { it.size })
        trace.channels.toSortedMap().forEach { (base, signal) ->
            val series = XYSeries(base.toString())
            for (index in start until minOf(end, signal.size)) series.add(index, signal[index])
            traceDataset.addSeries(series)
        }
        traceChart.title.text = "${chromatogram.name}: reference ${mismatch.refPos + 1}, read ${mismatch.readPos + 1} (${mismatch.kind})"
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
