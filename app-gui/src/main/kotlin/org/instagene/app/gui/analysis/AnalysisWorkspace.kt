package org.instagene.app.gui.analysis

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.ChromatogramRecord
import org.instagene.core.NcbiClient
import org.instagene.core.Seq
import java.awt.BorderLayout
import java.io.File
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * Analysis tools arranged as ordinary grouped tabs. Keeping the navigation in
 * standard tab controls makes the available workflows visible without the
 * bespoke category/sidebar-card interaction that previously duplicated tabs.
 */
internal class AnalysisWorkspace(
    private val onOpenSequence: (Seq) -> Unit,
    private val onReveal: (Int, Int) -> Unit,
    private val ncbiClient: NcbiClient,
    private val ncbiPollIntervalMillis: Long,
    private val prefs: Prefs,
    private val onDetached: (BoundAnalysisPanel, String, () -> Unit) -> Unit,
) : JPanel(BorderLayout()) {

    private var doc = SeqDocument(Seq(""))
    internal val categoryTabs = JTabbedPane()
    private val toolTabs = mutableMapOf<ToolCategory, JTabbedPane>()
    private val toolHosts = mutableMapOf<String, JPanel>()
    private var activeToolName: String? = null
    private val detachedPanels = mutableListOf<BoundAnalysisPanel>()

    private val panelFactories: Map<String, () -> BoundAnalysisPanel> = mapOf(
        "Search" to { SearchAnalysisPanel(onReveal) },
        "Alignment" to { AlignmentAnalysisPanel(prefs) },
        "Repeats / Dot Plot" to { RepeatAnalysisPanel(prefs) },
        "Enzymes" to { EnzymeAnalysisPanel() },
        "CpG Methylation" to { CpGAnalysisPanel() },
        "Assembly" to { AssemblyAnalysisPanel(onOpenSequence) },
        "PCR / Mutagenesis" to { PcrAnalysisPanel(onOpenSequence) },
        "Translation / Structure" to { TranslationAnalysisPanel(onOpenSequence) },
        "Virtual Gel" to { GelAnalysisPanel() },
        "Calculators" to { CalculatorAnalysisPanel() },
        "NCBI / BLAST" to { NcbiAnalysisPanel(onOpenSequence, ncbiClient, ncbiPollIntervalMillis, prefs) },
        "Chromatogram" to { ChromatogramAnalysisPanel() },
        "CRISPR / gRNA" to { CrisprDesignAnalysisPanel() },
        "Sanger Alignment" to { SangerAlignmentAnalysisPanel(prefs) },
        "Primer Thermo" to { PrimerThermodynamicsAnalysisPanel() },
        "Plasmid DB" to { PlasmidDatabaseAnalysisPanel(onOpenSequence) },
        "Site Domestication" to { SiteDomesticationAnalysisPanel() },
        "Statistics / Graphs" to { GraphAnalysisPanel(prefs) },
    )
    private val activePanels = mutableMapOf<String, BoundAnalysisPanel>()

    private val categories: LinkedHashMap<ToolCategory, List<String>> = linkedMapOf(
        ToolCategory.SEARCH to listOf("Search", "NCBI / BLAST"),
        ToolCategory.SEQUENCE to listOf("Alignment", "Repeats / Dot Plot", "CpG Methylation", "Statistics / Graphs", "Translation / Structure"),
        ToolCategory.CLONING to listOf("Enzymes", "Assembly", "Site Domestication", "CRISPR / gRNA"),
        ToolCategory.PCR to listOf("PCR / Mutagenesis", "Virtual Gel", "Chromatogram", "Sanger Alignment"),
        ToolCategory.UTILITIES to listOf("Calculators", "Primer Thermo", "Plasmid DB"),
    )

    init {
        categories.forEach { (category, names) ->
            val tabs = JTabbedPane().apply {
                names.forEach { name ->
                    val host = JPanel(BorderLayout()).apply {
                        add(JLabel("Select $name", JLabel.CENTER), BorderLayout.CENTER)
                    }
                    toolHosts[name] = host
                    addTab(name, host)
                }
                addChangeListener { activateSelectedTool(category) }
            }
            toolTabs[category] = tabs
            categoryTabs.addTab(category.displayName, tabs)
        }
        categoryTabs.addChangeListener { activeCategory()?.let(::activateSelectedTool) }
        add(categoryTabs, BorderLayout.CENTER)

        selectTool(prefs.value.analysisDefaults.lastTool.takeIf { it in toolNames() } ?: "Search")
    }

    fun bindDocument(newDoc: SeqDocument) {
        doc = newDoc
        activePanels.values.forEach { it.bindDocument(newDoc) }
        detachedPanels.forEach { it.bindDocument(newDoc) }
    }

    fun selectTool(name: String) {
        val category = categories.entries.firstOrNull { name in it.value }?.key ?: return
        val categoryIndex = categories.keys.indexOf(category)
        if (categoryIndex >= 0) categoryTabs.selectedIndex = categoryIndex
        val tabs = toolTabs.getValue(category)
        val toolIndex = tabs.indexOfTab(name)
        if (toolIndex >= 0) tabs.selectedIndex = toolIndex
        activateTool(name)
    }

    /** Routes a trace opened from the common file chooser into its inspector. */
    fun showChromatogram(record: ChromatogramRecord, sourceFile: File? = null) {
        activateTool("Chromatogram")
        (activePanels["Chromatogram"] as? ChromatogramAnalysisPanel)
            ?.showChromatogram(record, sourceFile)
    }

    /** Routes an aligned multi-record file into the alignment viewer. */
    fun showAlignment(sequences: List<Seq>, sourceFile: File? = null) {
        activateTool("Alignment")
        (activePanels["Alignment"] as? AlignmentAnalysisPanel)
            ?.showImportedAlignment(sequences, sourceFile)
    }

    /** Routes already-parsed chromatogram reads to Sanger verification for the currently bound reference. */
    fun showSangerVerification(records: List<ChromatogramRecord>, sourceFiles: List<File>) {
        activateTool("Sanger Alignment")
        (activePanels["Sanger Alignment"] as? SangerAlignmentAnalysisPanel)
            ?.showDroppedReads(records, sourceFiles)
    }

    private fun activeCategory(): ToolCategory? = categories.keys.elementAtOrNull(categoryTabs.selectedIndex)

    private fun activateSelectedTool(category: ToolCategory) {
        val tabs = toolTabs.getValue(category)
        val name = categories.getValue(category).getOrNull(tabs.selectedIndex) ?: return
        activateTool(name)
    }

    private fun activateTool(name: String) {
        val factory = panelFactories[name] ?: return
        val panel = activePanels.getOrPut(name) { factory().also { it.bindDocument(doc) } }
        val host = toolHosts.getValue(name)
        if (host.getComponent(0) !== panel) {
            host.removeAll()
            host.add(ToolHeader(name) { openExternalTool(name) }, BorderLayout.NORTH)
            host.add(panel, BorderLayout.CENTER)
            host.revalidate()
            host.repaint()
        }
        activeToolName = name
        if (prefs.value.analysisDefaults.lastTool != name) {
            prefs.update { current -> current.copy(analysisDefaults = current.analysisDefaults.copy(lastTool = name)) }
        }
    }

    /**
     * Opens a separate analysis instance instead of moving the selected tab's
     * component. The main tab therefore remains selected and menu actions keep
     * targeting the workflow the user chose.
     */
    internal fun openExternalTool(name: String) {
        val panel = panelFactories[name]?.invoke()?.also { it.bindDocument(doc) } ?: return
        detachedPanels += panel
        onDetached(panel, name) { detachedPanels.remove(panel) }
    }

    fun toolNames(): List<String> = listOf(
        "Search", "Alignment", "Repeats / Dot Plot", "Enzymes", "CpG Methylation", "Assembly", "PCR / Mutagenesis", "Translation / Structure",
        "Virtual Gel", "Calculators", "NCBI / BLAST", "Chromatogram",
        "CRISPR / gRNA", "Sanger Alignment", "Primer Thermo", "Plasmid DB", "Site Domestication",
        "Statistics / Graphs",
    )

    fun selectedTool(): String? = activeToolName
}
