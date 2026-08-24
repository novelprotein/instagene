package org.instagene.app.gui.analysis

import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.*
import org.instagene.core.io.SeqIO
import java.awt.*
import java.io.File
import javax.swing.*

/** Interactive dot-plot plus direct/inverted-repeat view for the active sequence. */
internal class RepeatAnalysisPanel(private val prefs: Prefs) : BoundAnalysisPanel() {
    private val savedSettings = prefs.value.analysisDefaults
    private val wordSize = JSpinner(SpinnerNumberModel(savedSettings.repeatWordSize.coerceIn(1, 1_000), 1, 1_000, 1))
    private val minimumLength = JSpinner(SpinnerNumberModel(savedSettings.repeatMinimumLength.coerceIn(1, 10_000), 1, 10_000, 1))
    private val maxPoints = JSpinner(SpinnerNumberModel(savedSettings.repeatMaxPoints.coerceIn(100, 200_000), 100, 200_000, 100))
    private val includeInverted = JCheckBox("Show inverted matches", savedSettings.repeatIncludeInverted)
    private val queryName = JTextField("Self comparison", 18).apply { isEditable = false }
    private val canvas = DotPlotCanvas()
    private val output = output()
    private val run = JButton("Analyze")
    private val exportPlot = JButton("Export plot").apply { isEnabled = false }
    private val exportRepeats = JButton("Export repeats").apply { isEnabled = false }
    private var queryFile: File? = null
    private var task: SwingWorker<AnalysisRun, Unit>? = null
    private var displayed: AnalysisRun? = null

    init {
        val chooseQuery = JButton("Compare to…").apply {
            toolTipText = "Optionally compare the active sequence against a second sequence; leave self comparison for repeat discovery."
            addActionListener { chooseQuery() }
        }
        val clearQuery = JButton("Use self").apply {
            toolTipText = "Use the active sequence on both axes."
            addActionListener {
                queryFile = null
                queryName.text = "Self comparison"
                clearResults()
            }
        }
        run.toolTipText = "Build a bounded k-mer dot plot and call direct/inverted repeats. Click again to cancel."
        run.addActionListener { if (task == null) analyze() else cancel() }
        exportPlot.toolTipText = "Export the displayed dot plot as SVG, JSON, or TSV according to the file extension."
        exportPlot.addActionListener { exportPlot() }
        exportRepeats.toolTipText = "Export direct/inverted repeat calls as JSON or TSV according to the file extension."
        exportRepeats.addActionListener { exportRepeats() }

        add(
            row(
                chooseQuery, clearQuery, queryName, JLabel("Word"), wordSize, JLabel("Min repeat"), minimumLength,
                JLabel("Max points"), maxPoints, includeInverted, run, exportPlot, exportRepeats,
            ),
            BorderLayout.NORTH,
        )
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, canvas, JScrollPane(output)).apply {
            resizeWeight = 0.7
            dividerLocation = 430
        }
        add(split, BorderLayout.CENTER)
    }

    override fun refreshDocument() = clearResults()

    private fun chooseQuery() {
        val chooser = JFileChooser().apply { dialogTitle = "Choose dot-plot comparison sequence" }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        queryFile = chooser.selectedFile
        queryName.text = chooser.selectedFile.name
        clearResults()
    }

    private fun analyze() {
        val source = doc.seq
        val selectedQuery = queryFile
        val word = wordSize.value as Int
        val min = minimumLength.value as Int
        val pointCap = maxPoints.value as Int
        val inverted = includeInverted.isSelected
        prefs.update { current ->
            current.copy(
                analysisDefaults = current.analysisDefaults.copy(
                    repeatWordSize = word,
                    repeatMinimumLength = min,
                    repeatMaxPoints = pointCap,
                    repeatIncludeInverted = inverted,
                ),
            )
        }
        if (inverted && source.kind == SeqKind.PROTEIN) {
            output.text = "Inverted-repeat analysis requires a DNA or RNA sequence. Uncheck ‘Show inverted matches’ for direct protein self-matches."
            return
        }
        run.text = "Cancel"
        output.text = "Analyzing k-mers and extending repeat seeds…"
        val worker = object : SwingWorker<AnalysisRun, Unit>() {
            override fun doInBackground(): AnalysisRun {
                val comparison = selectedQuery?.let(SeqIO::read) ?: source
                val dot = RepeatAnalysis.dotPlot(source, comparison, word, inverted, pointCap)
                val repeats = RepeatAnalysis.findRepeats(source, min, maxResults = pointCap, includeInverted = inverted)
                return AnalysisRun(dot, repeats)
            }

            override fun done() {
                if (task !== this) return
                task = null
                run.text = "Analyze"
                if (isCancelled) {
                    output.text = "Repeat analysis cancelled."
                    return
                }
                runCatching(::get).onSuccess { result ->
                    displayed = result
                    canvas.result = result.dotPlot
                    output.text = render(result)
                    exportPlot.isEnabled = true
                    exportRepeats.isEnabled = true
                }.onFailure { error -> output.text = error.message ?: "Repeat analysis failed" }
            }
        }
        task = worker
        worker.execute()
    }

    private fun cancel() {
        task?.cancel(true)
        task = null
        run.text = "Analyze"
        output.text = "Repeat analysis cancelled."
    }

    private fun clearResults() {
        task?.cancel(true)
        task = null
        run.text = "Analyze"
        displayed = null
        canvas.result = null
        output.text = "Choose analysis settings, then click Analyze. Direct matches are blue; reverse-complement matches are magenta."
        exportPlot.isEnabled = false
        exportRepeats.isEnabled = false
    }

    private fun exportPlot() {
        val result = displayed?.dotPlot ?: return
        val chooser = JFileChooser().apply { dialogTitle = "Export dot plot (.svg, .json, or .tsv)" }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching {
            val file = chooser.selectedFile
            val content = when (file.extension.lowercase()) {
                "svg" -> RepeatAnalysis.dotPlotSvg(result)
                "json" -> RepeatAnalysis.dotPlotJson(result)
                else -> RepeatAnalysis.dotPlotTsv(result)
            }
            file.writeText(content)
        }.onFailure { showExportError(it.message ?: "Unable to export dot plot") }
    }

    private fun exportRepeats() {
        val result = displayed?.repeats ?: return
        val chooser = JFileChooser().apply { dialogTitle = "Export repeat calls (.json or .tsv)" }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching {
            val file = chooser.selectedFile
            file.writeText(if (file.extension.equals("json", ignoreCase = true)) RepeatAnalysis.repeatsJson(result) else RepeatAnalysis.repeatsTsv(result))
        }.onFailure { showExportError(it.message ?: "Unable to export repeats") }
    }

    private fun showExportError(message: String) = javax.swing.JOptionPane.showMessageDialog(
        this,
        message,
        "Repeat analysis export",
        javax.swing.JOptionPane.ERROR_MESSAGE,
    )

    private fun render(result: AnalysisRun): String = buildString {
        val dot = result.dotPlot
        val repeats = result.repeats
        appendLine("Dot plot: ${dot.horizontalName} (${dot.horizontalLength}) × ${dot.verticalName} (${dot.verticalLength})")
        appendLine("Word size: ${dot.wordSize}; points: ${dot.points.size}${if (dot.truncated) " (capped)" else ""}")
        appendLine("Direct points: ${dot.points.count { it.orientation == RepeatOrientation.DIRECT }}")
        appendLine("Inverted points: ${dot.points.count { it.orientation == RepeatOrientation.INVERTED }}")
        appendLine()
        appendLine("Repeat calls: ${repeats.repeats.size}${if (repeats.truncated) " (capped)" else ""}")
        appendLine("Direct: ${repeats.directRepeats.size}; inverted: ${repeats.invertedRepeats.size}")
        appendLine("orientation\tfirst (1-based)\tsecond (1-based)\tlength\tsequence")
        repeats.repeats.take(200).forEach { repeat ->
            appendLine("${repeat.orientation}\t${repeat.firstStart + 1}..${repeat.firstEnd}\t${repeat.secondStart + 1}..${repeat.secondEnd}\t${repeat.length}\t${repeat.sequence}")
        }
        if (repeats.repeats.size > 200) appendLine("… ${repeats.repeats.size - 200} additional repeat calls; export for the complete result.")
    }

    private data class AnalysisRun(val dotPlot: DotPlotResult, val repeats: RepeatAnalysisResult)
}

/** Lightweight renderer that does not require a charting dependency or another analysis pass. */
private class DotPlotCanvas : JPanel(BorderLayout()) {
    var result: DotPlotResult? = null
        set(value) {
            field = value
            repaint()
        }

    init {
        preferredSize = Dimension(760, 470)
        background = Color.WHITE
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val dot = result ?: run {
            g.color = Color(0x546E7A)
            g.drawString("Run analysis to display a dot plot.", 20, 28)
            return
        }
        val margin = 54.0
        val plotWidth = (width - margin * 1.5).coerceAtLeast(1.0)
        val plotHeight = (height - margin * 1.5).coerceAtLeast(1.0)
        fun x(position: Int) = margin + position.toDouble() / dot.horizontalLength.coerceAtLeast(1) * plotWidth
        fun y(position: Int) = margin + position.toDouble() / dot.verticalLength.coerceAtLeast(1) * plotHeight
        g.color = Color(0xFAFAFA)
        g.fillRect(margin.toInt(), margin.toInt(), plotWidth.toInt(), plotHeight.toInt())
        g.color = Color(0x455A64)
        g.stroke = BasicStroke(1f)
        g.drawRect(margin.toInt(), margin.toInt(), plotWidth.toInt(), plotHeight.toInt())
        dot.points.forEach { point ->
            g.color = if (point.orientation == RepeatOrientation.DIRECT) Color(0x1565C0) else Color(0xAD1457)
            g.fillOval((x(point.horizontalPosition) - 1).toInt(), (y(point.verticalPosition) - 1).toInt(), 3, 3)
        }
        g.color = Color(0x263238)
        g.drawString("${dot.horizontalName} (${dot.horizontalLength})", margin.toInt(), height - 14)
        g.drawString("${dot.verticalName} (${dot.verticalLength})", 8, margin.toInt() - 12)
        g.color = Color(0x1565C0)
        g.drawString("● direct", width - 170, 20)
        g.color = Color(0xAD1457)
        g.drawString("● inverted", width - 90, 20)
        if (dot.truncated) {
            g.color = Color(0xB71C1C)
            g.drawString("Capped at ${dot.points.size} points", margin.toInt(), 20)
        }
    }
}
