@file:Suppress("DuplicatedCode")

package org.instagene.app.gui.analysis

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.*
import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartPanel
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.labels.StandardPieSectionLabelGenerator
import org.jfree.chart.plot.CategoryPlot
import org.jfree.chart.plot.PiePlot
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.category.BarRenderer
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.data.category.DefaultCategoryDataset
import org.jfree.data.general.DefaultPieDataset
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import java.awt.*
import java.text.DecimalFormat
import javax.swing.*

internal class GraphAnalysisPanel(private val prefs: Prefs) : BoundAnalysisPanel() {
    private val chartTabs = JTabbedPane()
    private val infoArea = output()
    private val windowSize = JSpinner(SpinnerNumberModel(prefs.value.graphWindowSize, 10, 10000, 10))
    private val stepSize = JSpinner(SpinnerNumberModel(prefs.value.graphStepSize, 1, 5000, 5))
    private val orfMinAa = JSpinner(SpinnerNumberModel(prefs.value.graphOrfMinAa, 10, 500, 5))
    private val orfWindowSize = JSpinner(SpinnerNumberModel(prefs.value.graphOrfWindowSize, 50, 5000, 50))

    private var cachedSeqIdentity: Any? = null
    private var cachedStats: SequenceStats? = null
    private var cachedCodons: List<CodonUsageEntry>? = null
    private var cachedDinuc: List<Bar>? = null
    private var cachedRepeats: List<RepeatMatch>? = null
    private var cachedCpGIslands: List<CpGIsland>? = null
    private var loadingGraphPreferences = false

    private var statsWorker: SwingWorker<SequenceStats, Nothing>? = null
    private val tabWorkers = mutableMapOf<Int, SwingWorker<JComponent, Nothing>>()

    private val chartBuilders = linkedMapOf(
        "Overview" to { buildPieChart() },
        "Composition" to { buildCompositionChart() },
        "GC Content" to { buildGcContentChart() },
        "GC Skew" to { buildGcSkewChart() },
        "Cumul. GC Skew" to { buildCumulativeGcSkewChart() },
        "Melting Temp" to { buildMeltingTempChart() },
        "Codon Usage" to { buildCodonUsageChart() },
        "Dinucleotides" to { buildDinucleotideChart() },
        "CpG Density" to { buildCpgDensityChart() },
        "CpG Islands" to { buildCpgIslandsChart() },
        "ORF Density" to { buildOrfDensityChart() },
        "Repeats" to { buildRepeatsChart() },
        "Summary" to { buildSummaryPanel() },
    )
    private val builtTabs = mutableSetOf<Int>()

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JLabel("Window:"))
            add(windowSize)
            add(JLabel("Step:"))
            add(stepSize)
            add(JLabel("ORF min AA:"))
            add(orfMinAa)
            add(JLabel("ORF window:"))
            add(orfWindowSize)
            val refresh = JButton("Refresh")
            refresh.addActionListener { cancelAllWorkers(); invalidateCache(); rebuildAll() }
            add(refresh)
            val exportInfo = JButton("Export info")
            exportInfo.addActionListener { exportStats() }
            add(exportInfo)
        }
        add(toolbar, BorderLayout.NORTH)

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, chartTabs, JScrollPane(infoArea))
        split.dividerLocation = 500
        split.resizeWeight = 0.7
        add(split, BorderLayout.CENTER)

        chartTabs.addChangeListener { buildSelectedTab() }
        listOf(windowSize, stepSize, orfMinAa, orfWindowSize).forEach { spinner ->
            spinner.addChangeListener { saveGraphPreferences() }
        }
        prefs.addListener { loadGraphPreferences() }
    }

    private fun saveGraphPreferences() {
        if (loadingGraphPreferences) return
        prefs.update {
            it.copy(
                graphWindowSize = windowSize.value as Int,
                graphStepSize = stepSize.value as Int,
                graphOrfMinAa = orfMinAa.value as Int,
                graphOrfWindowSize = orfWindowSize.value as Int,
            )
        }
    }

    private fun loadGraphPreferences() {
        loadingGraphPreferences = true
        try {
            val current = prefs.value
            if (windowSize.value != current.graphWindowSize) windowSize.value = current.graphWindowSize
            if (stepSize.value != current.graphStepSize) stepSize.value = current.graphStepSize
            if (orfMinAa.value != current.graphOrfMinAa) orfMinAa.value = current.graphOrfMinAa
            if (orfWindowSize.value != current.graphOrfWindowSize) orfWindowSize.value = current.graphOrfWindowSize
        } finally {
            loadingGraphPreferences = false
        }
    }

    override fun refreshDocument() {
        val seq = doc.seq
        if (seq.kind == SeqKind.PROTEIN) {
            cancelAllWorkers()
            invalidateCache()
            rebuildAll()
            return
        }
        val identity = System.identityHashCode(seq) to seq.bases.length
        if (identity != cachedSeqIdentity) {
            cancelAllWorkers()
            invalidateCache()
            rebuildAll()
        }
    }

    private fun cancelAllWorkers() {
        statsWorker?.cancel(true)
        statsWorker = null
        for ((_, w) in tabWorkers) w.cancel(true)
        tabWorkers.clear()
    }

    private fun invalidateCache() {
        cachedSeqIdentity = null
        cachedStats = null
        cachedCodons = null
        cachedDinuc = null
        cachedRepeats = null
        cachedCpGIslands = null
        builtTabs.clear()
        chartTabs.removeAll()
    }

    // --------------------------------------------------------- async rebuild

    private fun rebuildAll() {
        val seq = doc.seq
        if (seq.kind == SeqKind.PROTEIN) {
            chartTabs.removeAll()
            chartTabs.addTab("Amino Acid Composition", buildPieChart())
            infoArea.text = buildProteinInfo(seq)
            return
        }

        chartTabs.removeAll()
        val tabNames = mutableListOf("Overview", "Composition", "GC Content", "GC Skew",
            "Cumul. GC Skew", "Melting Temp")
        if (seq.kind == SeqKind.DNA) tabNames += "Codon Usage"
        tabNames += "Dinucleotides"
        if (seq.kind == SeqKind.DNA) {
            tabNames += "CpG Density"
            tabNames += "CpG Islands"
        }
        if (seq.kind == SeqKind.DNA) tabNames += "ORF Density"
        tabNames += "Repeats"
        tabNames += "Summary"
        for (name in tabNames) {
            chartTabs.addTab(name, loadingPanel())
        }

        infoArea.text = "Computing statistics\u2026"
        startStatsWorker()
    }

    private fun startStatsWorker() {
        val seq = doc.seq
        cachedSeqIdentity = System.identityHashCode(seq) to seq.bases.length

        statsWorker = object : SwingWorker<SequenceStats, Nothing>() {
            override fun doInBackground(): SequenceStats =
                SequenceStatistics.computeStats(seq)

            override fun done() {
                if (isCancelled) return
                try {
                    cachedStats = get()
                    infoArea.text = buildStatsText()
                    buildSelectedTab()
                } catch (ex: Exception) {
                    System.err.println("instagene: graph stats computation failed: ${ex.message}")
                }
            }
        }.also { it.execute() }
    }

    // --------------------------------------------------------- per-tab SwingWorker

    private fun buildSelectedTab() {
        val idx = chartTabs.selectedIndex
        if (idx < 0 || idx in builtTabs) return
        if (cachedStats == null) return

        val title = chartTabs.getTitleAt(idx) ?: return
        val builder = chartBuilders[title] ?: return

        tabWorkers.remove(idx)?.cancel(true)

        val worker = object : SwingWorker<JComponent, Nothing>() {
            override fun doInBackground(): JComponent = builder()

            override fun done() {
                if (isCancelled) return
                try {
                    chartTabs.setComponentAt(idx, get())
                    builtTabs += idx
                } catch (ex: Exception) {
                    System.err.println("instagene: chart build failed: ${ex.message}")
                }
            }
        }
        tabWorkers[idx] = worker
        worker.execute()
    }

    private fun loadingPanel(): JPanel = JPanel(BorderLayout()).apply {
        add(JLabel("Computing statistics\u2026", SwingConstants.CENTER), BorderLayout.CENTER)
    }

    // --------------------------------------------------------- chart builders

    private fun buildPieChart(): ChartPanel {
        val stats = cachedStats!!
        val ds = DefaultPieDataset<String>()
        for ((base, count) in stats.nucleotideComposition) {
            if (count > 0) ds.setValue("$base", count)
        }
        val chart = ChartFactory.createPieChart("Nucleotide Composition", ds, true, true, false)
        chart.backgroundPaint = Color.WHITE
        @Suppress("UNCHECKED_CAST")
        val plot = chart.plot as PiePlot<String>
        plot.labelFont = Font("SansSerif", Font.PLAIN, 10)
        val colorMap = mapOf(
            'A' to Color(0x1B5E20), 'T' to Color(0xB71C1C), 'U' to Color(0xB71C1C),
            'G' to Color(0x0D47A1), 'C' to Color(0xF57F17), 'N' to Color(0x9E9E9E),
        )
        for (base in stats.nucleotideComposition.keys) {
            plot.setSectionPaint("$base", colorMap[base] ?: Color.GRAY)
        }
        plot.labelGenerator = StandardPieSectionLabelGenerator(
            "{0}: {1} ({2})", DecimalFormat("#,##0"), DecimalFormat("0.0%")
        )
        return ChartPanel(chart)
    }

    private fun buildCompositionChart(): ChartPanel {
        val dataset = DefaultCategoryDataset()
        val comp = SequenceStatistics.nucleotideComposition(doc.seq)
        for ((label, value) in comp) dataset.addValue(value, "Percentage", label)
        val chart = ChartFactory.createBarChart(
            "Base Composition (%)", "Base", "Percentage",
            dataset, PlotOrientation.VERTICAL, true, false, false
        )
        chart.backgroundPaint = Color.WHITE
        val plot = chart.plot as CategoryPlot
        plot.backgroundPaint = Color.WHITE
        val renderer = BarRenderer()
        renderer.setDrawBarOutline(true)
        renderer.setSeriesPaint(0, Color(0x1565C0))
        renderer.maximumBarWidth = 0.08
        plot.renderer = renderer
        return ChartPanel(chart)
    }

    private fun buildGcContentChart(): ChartPanel {
        val ws = (windowSize.value as Number).toInt()
        val st = (stepSize.value as Number).toInt()
        val data = SequenceStatistics.gcContentProfile(doc.seq, ws, st)
        if (data.isEmpty()) return emptyChart("GC Content \u2014 no data (sequence too short)")
        return buildXYChart("GC Content (window=$ws, step=$st)", "Position", "GC%", data, Color(0x0D47A1)) { plot ->
            (plot.rangeAxis as NumberAxis).setRange(0.0, 100.0)
        }
    }

    private fun buildGcSkewChart(): ChartPanel {
        val ws = (windowSize.value as Number).toInt()
        val st = (stepSize.value as Number).toInt()
        val data = SequenceStatistics.gcSkewProfile(doc.seq, ws, st)
        if (data.isEmpty()) return emptyChart("GC Skew \u2014 no data (sequence too short)")
        return buildXYChart("GC Skew (window=$ws, step=$st)", "Position", "Skew", data, Color(0x4A148C))
    }

    private fun buildCumulativeGcSkewChart(): ChartPanel {
        val ws = (windowSize.value as Number).toInt()
        val st = (stepSize.value as Number).toInt()
        val data = SequenceStatistics.cumulativeGcSkew(doc.seq, ws, st)
        if (data.isEmpty()) return emptyChart("Cumulative GC Skew \u2014 no data")
        return buildXYChart("Cumulative GC Skew (window=$ws, step=$st)", "Position", "Skew", data, Color(0x6A1B9A))
    }

    private fun buildMeltingTempChart(): ChartPanel {
        val ws = (windowSize.value as Number).toInt()
        val st = (stepSize.value as Number).toInt()
        val data = SequenceStatistics.meltingTempProfile(doc.seq, ws, st)
        if (data.isEmpty()) return emptyChart("Melting Temp \u2014 no data (sequence too short)")
        return buildXYChart("Melting Temperature (window=$ws, step=$st)", "Position", "Tm (\u00B0C)", data, Color(0xE65100))
    }

    private fun buildCodonUsageChart(): ChartPanel {
        val usage = (cachedCodons ?: SequenceStatistics.codonUsage(doc.seq).also { cachedCodons = it }).take(30)
        if (usage.isEmpty()) return emptyChart("Codon Usage \u2014 no codons found")
        val dataset = DefaultCategoryDataset()
        for ((codon, _, frequency) in usage) dataset.addValue(frequency, "Frequency (\u2030)", codon)
        return buildBarChart("Top 30 Codon Usage", "Codon", "Freq (\u2030)", dataset, Color(0x2E7D32))
    }

    private fun buildDinucleotideChart(): ChartPanel {
        val data = cachedDinuc ?: SequenceStatistics.dinucleotideFrequencies(doc.seq).also { cachedDinuc = it }
        if (data.isEmpty()) return emptyChart("Dinucleotides \u2014 no data")
        val dataset = DefaultCategoryDataset()
        for ((label, value) in data) dataset.addValue(value, "Frequency (%)", label)
        return buildBarChart("Dinucleotide Frequencies", "Dinucleotide", "%", dataset, Color(0x00695C))
    }

    private fun buildOrfDensityChart(): ChartPanel {
        val ws = (orfWindowSize.value as Number).toInt()
        val st = (stepSize.value as Number).toInt()
        val data = SequenceStatistics.orfDensity(doc.seq, ws, st)
        if (data.isEmpty()) return emptyChart("ORF Density \u2014 no data (sequence too short)")
        return buildXYChart("ORF Density (window=$ws, step=$st)", "Position", "Coverage %", data, Color(0x880E4F))
    }

    private fun buildRepeatsChart(): ChartPanel {
        val repeats = cachedRepeats ?: SequenceStatistics.tandemRepeats(doc.seq, minRepeats = 2).also { cachedRepeats = it }
        if (repeats.isEmpty()) return emptyChart("Tandem Repeats \u2014 none found (min 2 repeats)")
        val dataset = DefaultCategoryDataset()
        for ((start, length, unit) in repeats.take(40)) dataset.addValue(length.toDouble(), "Length", "$unit @ $start")
        return buildBarChart("Tandem Repeats (${repeats.size} found)", "Repeat", "Length (bp)", dataset, Color(0xBF360C))
    }

    private fun buildCpgDensityChart(): ChartPanel {
        val ws = (windowSize.value as Number).toInt()
        val st = (stepSize.value as Number).toInt()
        val data = SequenceStatistics.cpgDensityProfile(doc.seq, ws, st)
        if (data.isEmpty()) return emptyChart("CpG Density \u2014 no data (sequence too short)")
        return buildXYChart("CpG Observed/Expected Ratio (window=$ws, step=$st)", "Position", "O/E Ratio", data, Color(0x006064)) { plot ->
            (plot.rangeAxis as NumberAxis).setRange(0.0, 2.5)
        }
    }

    private fun buildCpgIslandsChart(): ChartPanel {
        val ws = (windowSize.value as Number).toInt()
        val st = (stepSize.value as Number).toInt()
        val densityData = SequenceStatistics.cpgDensityProfile(doc.seq, ws, st)
        if (densityData.isEmpty()) return emptyChart("CpG Islands \u2014 no data (sequence too short)")
        val islands = cachedCpGIslands ?: SequenceStatistics.cpgIslands(doc.seq, ws, st).also { cachedCpGIslands = it }

        val series = XYSeries("O/E Ratio")
        for ((x, y) in densityData) series.add(x, y)
        val ds = XYSeriesCollection(series)
        val chart = ChartFactory.createXYLineChart(
            "CpG Islands (${islands.size} found)", "Position", "O/E Ratio",
            ds, PlotOrientation.VERTICAL, false, false, false
        )
        chart.backgroundPaint = Color.WHITE
        val plot = chart.plot as XYPlot
        plot.backgroundPaint = Color.WHITE
        plot.domainGridlinePaint = Color(0xE0E0E0)
        plot.rangeGridlinePaint = Color(0xE0E0E0)
        val renderer = XYLineAndShapeRenderer(true, false)
        renderer.setSeriesPaint(0, Color(0x006064))
        renderer.setSeriesStroke(0, BasicStroke(1.5f))
        plot.renderer = renderer
        (plot.rangeAxis as NumberAxis).setRange(0.0, 2.5)

        for ((start, end) in islands) {
            val islandAnnotation = org.jfree.chart.annotations.XYBoxAnnotation(
                start.toDouble(), 0.6, end.toDouble(), 2.5,
                BasicStroke(1.0f), Color(0, 105, 92), Color(0, 105, 92, 40)
            )
            plot.addAnnotation(islandAnnotation)
        }
        return ChartPanel(chart)
    }

    private fun buildSummaryPanel(): ChartPanel {
        val ds = DefaultCategoryDataset()
        ds.addValue(0.0, "", "")
        val chart = ChartFactory.createBarChart(
            "Summary", "", "",
            ds, PlotOrientation.VERTICAL, false, false, false
        )
        chart.backgroundPaint = Color.WHITE
        chart.title.isVisible = false
        return ChartPanel(chart)
    }

    // --------------------------------------------------------- shared chart helpers

    private fun buildXYChart(title: String, xLabel: String, yLabel: String, data: List<XY>, color: Color, configure: ((XYPlot) -> Unit)? = null): ChartPanel {
        val series = XYSeries(title)
        for ((x, y) in data) series.add(x, y)
        val ds = XYSeriesCollection(series)
        val chart = ChartFactory.createXYLineChart(title, xLabel, yLabel, ds, PlotOrientation.VERTICAL, false, false, false)
        chart.backgroundPaint = Color.WHITE
        val plot = chart.plot as XYPlot
        plot.backgroundPaint = Color.WHITE
        plot.domainGridlinePaint = Color(0xE0E0E0)
        plot.rangeGridlinePaint = Color(0xE0E0E0)
        val renderer = XYLineAndShapeRenderer(true, false)
        renderer.setSeriesPaint(0, color)
        renderer.setSeriesStroke(0, BasicStroke(1.5f))
        plot.renderer = renderer
        configure?.invoke(plot)
        return ChartPanel(chart)
    }

    private fun buildBarChart(title: String, xLabel: String, yLabel: String, dataset: DefaultCategoryDataset, color: Color): ChartPanel {
        val chart = ChartFactory.createBarChart(title, xLabel, yLabel, dataset, PlotOrientation.VERTICAL, false, false, false)
        chart.backgroundPaint = Color.WHITE
        val plot = chart.plot as CategoryPlot
        plot.backgroundPaint = Color.WHITE
        val renderer = BarRenderer()
        renderer.setDrawBarOutline(true)
        renderer.setSeriesPaint(0, color)
        renderer.maximumBarWidth = 0.06
        plot.renderer = renderer
        return ChartPanel(chart)
    }

    private fun emptyChart(msg: String): ChartPanel {
        val ds = DefaultCategoryDataset()
        ds.addValue(0.0, "", "")
        val chart = ChartFactory.createBarChart(msg, "", "", ds, PlotOrientation.VERTICAL, false, false, false)
        chart.backgroundPaint = Color.WHITE
        return ChartPanel(chart)
    }

    // --------------------------------------------------------- text builders

    private fun buildStatsText(): String {
        val stats = cachedStats ?: return "Computing\u2026"
        val repeats = cachedRepeats ?: emptyList()
        val sb = StringBuilder()
        sb.appendLine("Length: ${stats.length} bp | GC: ${"%.2f".format(stats.gcContent)}% | Skew: ${"%.4f".format(stats.gcSkew)}")
        sb.appendLine("Homopolymer: ${stats.longestHomopolymer.first}x${stats.longestHomopolymer.second} | Ambiguous: ${stats.ambigCount} | Complexity: ${"%.1f".format(stats.complexityScore)}%")
        sb.appendLine()
        val topDinuc = stats.dinucleotideCounts.entries.sortedByDescending { it.value }.take(6)
        sb.appendLine("Top dinucleotides: ${topDinuc.joinToString { "${it.key}:${it.value}" }}")
        val topTrinuc = stats.trinucleotideCounts.entries.sortedByDescending { it.value }.take(6)
        sb.appendLine("Top trinucleotides: ${topTrinuc.joinToString { "${it.key}:${it.value}" }}")
        if (repeats.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Tandem repeats (${repeats.size}):")
            for ((start, length, unit) in repeats.take(20)) {
                sb.appendLine("  $start-${start + length}  ${unit}x${length / unit.length}")
            }
        }
        return sb.toString()
    }

    private fun buildSummaryText(): String {
        val seq = doc.seq
        val stats = cachedStats ?: SequenceStatistics.computeStats(seq)
        val repeats = cachedRepeats ?: emptyList()
        val sb = StringBuilder()
        sb.appendLine("SEQUENCE STATISTICS SUMMARY")
        sb.appendLine("=".repeat(44))
        sb.appendLine()
        sb.appendLine("  Name:          ${seq.name}")
        sb.appendLine("  Length:        ${stats.length} bp")
        sb.appendLine("  Kind:          ${seq.kind}")
        sb.appendLine("  Topology:      ${seq.topology}")
        sb.appendLine("  Features:      ${seq.features.size}")
        sb.appendLine()
        sb.appendLine("  --- Composition -------------------------------------")
        sb.appendLine("  GC content:    ${"%.2f".format(stats.gcContent)}%")
        sb.appendLine("  GC skew:       ${"%.4f".format(stats.gcSkew)}")
        sb.appendLine("  AT skew:       ${"%.4f".format(stats.atSkew)}")
        sb.appendLine("  Ambiguities:   ${stats.ambigCount}")
        sb.appendLine()
        sb.appendLine("  --- Diversity ---------------------------------------")
        sb.appendLine("  Shannon H':    ${"%.4f".format(stats.shannonEntropy)}")
        sb.appendLine("  Simpson 1-D:   ${"%.4f".format(stats.simpsonDiversity)}")
        sb.appendLine("  Complexity:    ${"%.1f".format(stats.complexityScore)}%")
        sb.appendLine()
        sb.appendLine("  --- Structure ---------------------------------------")
        sb.appendLine("  Longest homopolymer: ${stats.longestHomopolymer.first} x ${stats.longestHomopolymer.second}")
        if (seq.kind == SeqKind.DNA) {
            sb.appendLine("  ORFs:           (see ORF Density tab)")
        }
        sb.appendLine("  Tandem repeats: ${repeats.size}")
        sb.appendLine()
        sb.appendLine("  --- Features ----------------------------------------")
        for ((type, name, start, end) in seq.features) {
            sb.appendLine("  ${type.padEnd(18)} ${name.padEnd(24)} $start-$end")
        }
        sb.appendLine()
        sb.appendLine("=".repeat(44))
        return sb.toString()
    }

    private fun buildProteinInfo(seq: Seq): String {
        val sb = StringBuilder()
        sb.appendLine("Protein: ${seq.name} (${seq.length} aa)")
        sb.appendLine()
        val comp = SequenceStatistics.aminoAcidComposition(seq)
        for ((label, value) in comp) {
            val pct = "%.1f".format(value).padStart(5)
            val bars = "\u2588".repeat((value / 2).toInt().coerceAtMost(40))
            sb.appendLine("  ${label.padStart(5)}  ${pct}%  $bars")
        }
        return sb.toString()
    }

    private fun exportStats() {
        ContextMenus.copyToClipboard(buildSummaryText())
    }
}
