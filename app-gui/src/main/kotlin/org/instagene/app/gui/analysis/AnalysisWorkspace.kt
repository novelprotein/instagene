package org.instagene.app.gui.analysis

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.GraphAnalysisPanel
import org.instagene.core.NcbiClient
import org.instagene.core.Seq
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

internal class AnalysisWorkspace(
    private val onOpenSequence: (Seq) -> Unit,
    private val onReveal: (Int, Int) -> Unit,
    private val ncbiClient: NcbiClient,
    private val ncbiPollIntervalMillis: Long,
    private val onDetached: (DetachedToolWindow) -> Unit,
) : JPanel(BorderLayout()) {

    private var doc: SeqDocument

    private val categoryBar = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    }
    private val toolListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val toolListScroll = JScrollPane(toolListPanel).apply {
        preferredSize = Dimension(180, 0)
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val contentArea = JPanel(BorderLayout())
    private val placeholderLabel = JLabel("Select a tool from the sidebar").apply {
        horizontalAlignment = JLabel.CENTER
    }

    private var selectedCard: ToolCard? = null
    private var activePanel: BoundAnalysisPanel? = null
    private var activeToolName: String? = null
    private val detachedWindows = mutableListOf<DetachedToolWindow>()

    private val panelFactories: Map<String, () -> BoundAnalysisPanel>
    private val activePanels = mutableMapOf<String, BoundAnalysisPanel>()

    private val categories: LinkedHashMap<ToolCategory, List<String>> = linkedMapOf(
        ToolCategory.SEARCH to listOf("Search", "NCBI / BLAST"),
        ToolCategory.SEQUENCE to listOf("Alignment", "CpG Methylation", "Statistics / Graphs", "Translation / Structure"),
        ToolCategory.CLONING to listOf("Enzymes", "Assembly", "Site Domestication", "CRISPR / gRNA"),
        ToolCategory.PCR to listOf("PCR / Mutagenesis", "Virtual Gel", "Chromatogram", "Sanger Alignment"),
        ToolCategory.UTILITIES to listOf("Calculators", "Primer Thermo", "Plasmid DB"),
    )

    init {
        doc = SeqDocument(Seq(""))

        panelFactories = mapOf(
            "Search" to { SearchAnalysisPanel(onReveal) },
            "Alignment" to { AlignmentAnalysisPanel() },
            "Enzymes" to { EnzymeAnalysisPanel() },
            "CpG Methylation" to { CpGAnalysisPanel() },
            "Assembly" to { AssemblyAnalysisPanel(onOpenSequence) },
            "PCR / Mutagenesis" to { PcrAnalysisPanel(onOpenSequence) },
            "Translation / Structure" to { TranslationAnalysisPanel(onOpenSequence) },
            "Virtual Gel" to { GelAnalysisPanel() },
            "Calculators" to { CalculatorAnalysisPanel() },
            "NCBI / BLAST" to { NcbiAnalysisPanel(onOpenSequence, ncbiClient, ncbiPollIntervalMillis) },
            "Chromatogram" to { ChromatogramAnalysisPanel() },
            "CRISPR / gRNA" to { CrisprDesignAnalysisPanel() },
            "Sanger Alignment" to { SangerAlignmentAnalysisPanel() },
            "Primer Thermo" to { PrimerThermodynamicsAnalysisPanel() },
            "Plasmid DB" to { PlasmidDatabaseAnalysisPanel(onOpenSequence) },
            "Site Domestication" to { SiteDomesticationAnalysisPanel() },
            "Statistics / Graphs" to { GraphAnalysisPanel() },
        )

        ToolCategory.entries.forEach { cat ->
            val button = javax.swing.JButton("${iconLabel(cat)} ${cat.displayName}").apply {
                isFocusable = false
                addActionListener { selectCategory(cat) }
            }
            categoryBar.add(button)
        }

        contentArea.add(placeholderLabel, BorderLayout.CENTER)

        add(categoryBar, BorderLayout.NORTH)
        add(toolListScroll, BorderLayout.WEST)
        add(contentArea, BorderLayout.CENTER)

        selectCategory(ToolCategory.SEARCH)
    }

    fun bindDocument(newDoc: SeqDocument) {
        doc = newDoc
        activePanel?.bindDocument(newDoc)
        detachedWindows.forEach { it.bindDocument(newDoc) }
    }

    fun selectCategory(category: ToolCategory) {
        toolListPanel.removeAll()
        categories[category]?.forEach { name ->
            val card = ToolCard(name) { selectTool(name) }
            toolListPanel.add(card)
            if (name == activeToolName) {
                selectedCard?.setSelected(false)
                selectedCard = card
                card.setSelected(true)
            }
        }
        toolListPanel.revalidate()
        toolListPanel.repaint()
    }

    fun selectTool(name: String) {
        val factory = panelFactories[name] ?: return
        val livePanel = activePanels.getOrPut(name) { factory().also { it.bindDocument(doc) } }

        activePanel?.let { contentArea.remove(it) }

        val header = ToolHeader(name) { popOut(name) }
        contentArea.removeAll()
        contentArea.add(header, BorderLayout.NORTH)
        contentArea.add(livePanel, BorderLayout.CENTER)
        contentArea.revalidate()
        contentArea.repaint()

        activePanel = livePanel
        activeToolName = name

        val category = categories.entries.find { name in it.value }?.key
        if (category != null) {
            selectedCard?.setSelected(false)
            toolListPanel.components.filterIsInstance<ToolCard>()
                .firstOrNull { it.toolName == name }
                ?.let {
                    it.setSelected(true)
                    selectedCard = it
                }
        }
    }

    private fun popOut(name: String) {
        val panel = activePanels.remove(name) ?: return

        contentArea.removeAll()
        contentArea.add(placeholderLabel, BorderLayout.CENTER)
        activePanel = null
        activeToolName = null
        selectedCard?.setSelected(false)
        selectedCard = null
        contentArea.revalidate()
        contentArea.repaint()

        val window = DetachedToolWindow(panel, name) { w ->
            detachedWindows.remove(w)
        }
        detachedWindows.add(window)
        window.bindDocument(doc)
        onDetached(window)
    }

    fun toolNames(): List<String> = listOf(
        "Search", "Alignment", "Enzymes", "CpG Methylation", "Assembly", "PCR / Mutagenesis", "Translation / Structure",
        "Virtual Gel", "Calculators", "NCBI / BLAST", "Chromatogram",
        "CRISPR / gRNA", "Sanger Alignment", "Primer Thermo", "Plasmid DB", "Site Domestication",
        "Statistics / Graphs",
    )

    fun selectedTool(): String? = activeToolName
}
