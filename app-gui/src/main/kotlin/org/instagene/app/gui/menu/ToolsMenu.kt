package org.instagene.app.gui.menu

import org.instagene.app.gui.enzyme.EnzymeManagerDialog
import org.instagene.app.gui.enzyme.enzymePool
import org.instagene.app.gui.enzyme.findEnzyme
import org.instagene.app.gui.dialog.AnalysisDialogs
import org.instagene.app.gui.dialog.SettingsDialog
import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.DigestPanel
import org.instagene.app.gui.tool.FeaturesPanel
import org.instagene.app.gui.tool.LibraryPanel
import org.instagene.app.gui.tool.PrimersPanel
import org.instagene.core.Enzyme
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import org.instagene.core.Version
import java.awt.event.KeyEvent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import kotlin.collections.iterator

class ToolsMenu(
    private val doc: SeqDocument,
    private val digestPanel: DigestPanel,
    private val prefs: Prefs = Prefs(),
    private val featuresPanel: FeaturesPanel? = null,
    private val primersPanel: PrimersPanel? = null,
    private val libraryPanel: LibraryPanel? = null,
    private val onAnalysis: (String) -> Unit = {},
) {

    private val makeCircularItem = JMenuItem("Make Circular")
    private val makeLinearItem = JMenuItem("Make Linear")
    private val addEnzymeItem = JMenuItem("Add Enzyme...")
    private val clearEnzymesItem = JMenuItem("Clear All Enzymes")
    private val manageEnzymesItem = JMenuItem("Manage Enzymes...")
    private val alignItem = JMenuItem("Align Sequences...")
    private val gelItem = JMenuItem("Virtual Gel...")
    private val identityItem = JMenuItem("Sequence Identity...")
    private val calculatorItem = JMenuItem("Molecular Calculator...")
    private val settingsItem = JMenuItem("Settings...")
    private val diagnosticItem = JMenuItem("Diagnostic Sites...")

    private val addFeatureFromSelectionItem = JMenuItem("Add Feature from Selection...")
    private val addFeatureManualItem = JMenuItem("Add Feature Manually...")
    private val autoAnnotateItem = JMenuItem("Auto-annotate...")
    private val editFeatureElementItem = JMenuItem("Edit Element...")
    private val saveFeatureItem = JMenuItem("Save Feature to Library")
    private val deleteFeatureItem = JMenuItem("Delete")

    private val designPrimersItem = JMenuItem("Design Primers...")
    private val advancedCandidatesItem = JMenuItem("Advanced Candidates...")
    private val copyFastaItem = JMenuItem("Copy as FASTA")
    private val savePrimersItem = JMenuItem("Save Primers to Library")
    private val addPrimersToFeaturesItem = JMenuItem("Add Primers to Features")
    private val editPrimerElementItem = JMenuItem("Edit Element...")

    private val addLibraryItem = JMenuItem("Add Item...")
    private val insertAtCaretItem = JMenuItem("Insert at Caret")
    private val copyLibraryItem = JMenuItem("Copy")
    private val openLibraryItem = JMenuItem("Open as Sequence")
    private val jumpToSourceItem = JMenuItem("Jump to Source")
    private val editLibraryItem = JMenuItem("Edit Element...")
    private val deleteLibraryItem = JMenuItem("Delete")

    private val analysisMenu = createAnalysisMenu()
    private val commonEnzymesMenu = createCommonEnzymesMenu()
    private val featuresMenu = createFeaturesMenu()
    private val primersMenu = createPrimersMenu()
    private val libraryMenu = createLibraryMenu()

    init {
        doc.addListener { _, _ -> syncEnabled() }
        prefs.addListener {
            rebuildCommonEnzymes()
            syncEnabled()
        }
        libraryPanel?.onStateChanged = { syncEnabled() }
        syncEnabled()
    }

    /** Enables digestion and topology actions only for compatible sequence types. */
    private fun syncEnabled() {
        val seq = doc.seq
        val dna = seq.kind == SeqKind.DNA
        val nucleotide = seq.kind != SeqKind.PROTEIN
        addEnzymeItem.isEnabled = dna
        clearEnzymesItem.isEnabled = dna
        manageEnzymesItem.isEnabled = dna
        commonEnzymesMenu.isEnabled = dna
        gelItem.isEnabled = dna
        makeCircularItem.isEnabled = nucleotide && !seq.isCircular
        makeLinearItem.isEnabled = nucleotide && seq.isCircular
        diagnosticItem.isEnabled = digestPanel.selectedEnzymes().isNotEmpty()

        val fp = featuresPanel
        addFeatureFromSelectionItem.isEnabled = fp?.isAddEnabled() == true
        addFeatureManualItem.isEnabled = fp?.isManualAddEnabled() == true
        autoAnnotateItem.isEnabled = fp?.isAutoAnnotateEnabled() == true
        editFeatureElementItem.isEnabled = fp?.isEditElementEnabled() == true
        saveFeatureItem.isEnabled = fp?.isSaveFeatureEnabled() == true
        deleteFeatureItem.isEnabled = fp?.isDeleteEnabled() == true

        val pp = primersPanel
        designPrimersItem.isEnabled = pp?.isDesignEnabled() == true
        advancedCandidatesItem.isEnabled = nucleotide
        copyFastaItem.isEnabled = pp?.areResultActionsEnabled() == true
        savePrimersItem.isEnabled = pp?.areResultActionsEnabled() == true
        addPrimersToFeaturesItem.isEnabled = pp?.areResultActionsEnabled() == true
        editPrimerElementItem.isEnabled = pp?.isEditElementEnabled() == true

        val lp = libraryPanel
        addLibraryItem.isEnabled = true
        insertAtCaretItem.isEnabled = lp?.isInsertEnabled() == true
        copyLibraryItem.isEnabled = lp?.isCopyEnabled() == true
        openLibraryItem.isEnabled = lp?.isOpenEnabled() == true
        jumpToSourceItem.isEnabled = lp?.isJumpToSourceEnabled() == true
        editLibraryItem.isEnabled = lp?.isEditElementEnabled() == true
        deleteLibraryItem.isEnabled = lp?.isDeleteEnabled() == true
    }

    fun create(): JMenu {
        return JMenu("Tools").apply {
            mnemonic = KeyEvent.VK_T

            add(makeCircularItem.apply {
                addActionListener {
                    doc.mutate("make circular") { it.withTopology(Topology.CIRCULAR) }
                }
            })
            add(makeLinearItem.apply {
                addActionListener {
                    doc.mutate("make linear") { it.withTopology(Topology.LINEAR) }
                }
            })
            addSeparator()
            add(featuresMenu)
            add(primersMenu)
            add(libraryMenu)
            addSeparator()
            add(addEnzymeItem.apply {
                addActionListener { addEnzymeByDialog() }
            })
            add(manageEnzymesItem.apply {
                addActionListener { EnzymeManagerDialog(prefs).isVisible = true }
            })
            add(clearEnzymesItem.apply {
                addActionListener { digestPanel.selectEnzymes(emptyList()) }
            })
            addSeparator()
            add(commonEnzymesMenu)
            add(diagnosticItem.apply {
                addActionListener { AnalysisDialogs.showDiagnostic(null, doc, digestPanel.selectedEnzymes()) }
            })
            addSeparator()
            add(alignItem.apply { addActionListener { AnalysisDialogs.showAlignment(null, doc) } })
            add(gelItem.apply { addActionListener { AnalysisDialogs.showGel(null, doc) } })
            add(identityItem.apply { addActionListener { AnalysisDialogs.showIdentity(null, doc) } })
            add(calculatorItem.apply { addActionListener { AnalysisDialogs.showMolecularCalculator(null) } })
            add(analysisMenu)
            add(settingsItem.apply { addActionListener { SettingsDialog.show(null, prefs) } })
            addSeparator()
            add(createAboutItem())
        }
    }

    /** Method-oriented entry points mirroring the cloning and PCR workflow workspace. */
    fun createActions(): JMenu = JMenu("Actions").apply {
        mnemonic = KeyEvent.VK_A
        val cloning = JMenu("Cloning").apply {
            listOf(
                "Restriction Cloning", "Gateway Cloning", "Gibson Assembly", "NEBuilder HiFi Assembly",
                "In-Fusion Cloning", "TA / GC Cloning", "TOPO Cloning", "Golden Gate Assembly",
            ).forEach { label ->
                add(JMenuItem(label).apply {
                    toolTipText = "Open the Assembly workspace for $label."
                    addActionListener { onAnalysis("Assembly") }
                })
            }
        }
        val pcr = JMenu("PCR and Mutagenesis").apply {
            listOf("PCR", "Inverse PCR", "Overlap Extension PCR", "Primer-Directed Mutagenesis", "Anneal Oligos").forEach { label ->
                add(JMenuItem(label).apply {
                    toolTipText = "Open the PCR / Mutagenesis workspace for $label."
                    addActionListener { onAnalysis("PCR / Mutagenesis") }
                })
            }
        }
        add(cloning)
        add(pcr)
        add(JMenuItem("Translation and Codon Design").apply {
            toolTipText = "Open translation, ORF, reverse-translation, and codon-design tools."
            addActionListener { onAnalysis("Translation / Structure") }
        })
        add(JMenuItem("Simulate Agarose Gel").apply {
            toolTipText = "Open the configurable virtual agarose gel workspace."
            addActionListener { onAnalysis("Virtual Gel") }
        })
        addSeparator()
        add(JMenuItem("CRISPR Guide RNA Design").apply {
            toolTipText = "Design SpCas9 guide RNAs with PAM scanning and off-target scoring."
            addActionListener { onAnalysis("CRISPR / gRNA") }
        })
        add(JMenuItem("Primer Thermodynamic Analysis").apply {
            toolTipText = "Analyze primer Tm, ΔG, self-dimers, and hairpin potential."
            addActionListener { onAnalysis("Primer Thermo") }
        })
        add(JMenuItem("Sanger Read Alignment").apply {
            toolTipText = "Align ABI/SCF chromatogram reads to a reference sequence."
            addActionListener { onAnalysis("Sanger Alignment") }
        })
        add(JMenuItem("Plasmid Database").apply {
            toolTipText = "Browse the built-in plasmid database by name, marker, or organism."
            addActionListener { onAnalysis("Plasmid DB") }
        })
        add(JMenuItem("Site Domestication").apply {
            toolTipText = "Find and silently remove internal Golden Gate Type IIS enzyme sites."
            addActionListener { onAnalysis("Site Domestication") }
        })
        addSeparator()
        add(JMenuItem("Statistics & Graphs").apply {
            toolTipText = "Comprehensive sequence statistics, GC profiles, codon usage, and more."
            addActionListener { onAnalysis("Statistics / Graphs") }
        })
    }

    /** Feature annotation actions, mirroring the Features panel buttons. */
    private fun createFeaturesMenu(): JMenu = JMenu("Features").apply {
        add(addFeatureFromSelectionItem.apply {
            addActionListener { featuresPanel?.addFeatureDialog() }
        })
        add(addFeatureManualItem.apply {
            addActionListener { featuresPanel?.manualAddDialog() }
        })
        add(autoAnnotateItem.apply {
            addActionListener { featuresPanel?.autoAnnotateDialog() }
        })
        addSeparator()
        add(editFeatureElementItem.apply {
            addActionListener { featuresPanel?.editSelectedFeatureElement() }
        })
        add(saveFeatureItem.apply {
            addActionListener { featuresPanel?.saveSelectedFeature() }
        })
        add(deleteFeatureItem.apply {
            addActionListener { featuresPanel?.deleteSelectedFeature() }
        })
    }

    /** Primer design actions, mirroring the Primers panel buttons. */
    private fun createPrimersMenu(): JMenu = JMenu("Primers").apply {
        add(designPrimersItem.apply {
            addActionListener { primersPanel?.designAndPrompt() }
        })
        add(advancedCandidatesItem.apply {
            addActionListener { primersPanel?.showAdvancedCandidates() }
        })
        addSeparator()
        add(copyFastaItem.apply {
            addActionListener { primersPanel?.copyAsFasta() }
        })
        add(savePrimersItem.apply {
            addActionListener { primersPanel?.savePrimers() }
        })
        add(addPrimersToFeaturesItem.apply {
            addActionListener { primersPanel?.addPrimersToFeatures() }
        })
        add(editPrimerElementItem.apply {
            addActionListener { primersPanel?.editSelectedPrimerElement() }
        })
    }

    /** Library actions, mirroring the Library panel buttons. */
    private fun createLibraryMenu(): JMenu = JMenu("Library").apply {
        add(addLibraryItem.apply {
            addActionListener { libraryPanel?.showAddItemDialog() }
        })
        addSeparator()
        add(insertAtCaretItem.apply {
            addActionListener { libraryPanel?.insertSelectedRow() }
        })
        add(copyLibraryItem.apply {
            addActionListener { libraryPanel?.copySelectedRow() }
        })
        add(openLibraryItem.apply {
            addActionListener { libraryPanel?.openSelectedRow() }
        })
        add(jumpToSourceItem.apply {
            addActionListener { libraryPanel?.jumpToSourceRow() }
        })
        addSeparator()
        add(editLibraryItem.apply {
            addActionListener { libraryPanel?.editSelectedRow() }
        })
        add(deleteLibraryItem.apply {
            addActionListener { libraryPanel?.deleteSelectedRow() }
        })
    }

    private fun createAnalysisMenu(): JMenu = JMenu("Analysis Workspace").apply {
        listOf("Search", "Alignment", "Enzymes", "Assembly", "PCR / Mutagenesis", "Translation / Structure", "Virtual Gel", "Calculators", "NCBI / BLAST", "Chromatogram", "CRISPR / gRNA", "Sanger Alignment", "Primer Thermo", "Plasmid DB", "Site Domestication", "Statistics / Graphs").forEach { name ->
            add(JMenuItem(name).apply { addActionListener { onAnalysis(name) } })
        }
    }

    /** Resolves a name against the whole catalog; unknown names offer to be defined as novel enzymes. */
    private fun addEnzymeByDialog() {
        val enzymeName = JOptionPane.showInputDialog(null, "Enzyme name:", "BamHI")
        if (enzymeName == null || enzymeName.isBlank()) return
        val enzyme = prefs.value.findEnzyme(enzymeName)
        if (enzyme != null) {
            digestPanel.selectEnzymes(digestPanel.selectedEnzymes() + enzyme)
        } else {
            val choice = JOptionPane.showConfirmDialog(
                null,
                "'$enzymeName' is not a known enzyme.\nDefine it as a novel enzyme?",
                "Unknown Enzyme",
                JOptionPane.YES_NO_OPTION,
            )
            if (choice == JOptionPane.YES_OPTION) {
                EnzymeManagerDialog(prefs, initialName = enzymeName).isVisible = true
                // After the dialog the enzyme may now exist; select it.
                prefs.value.findEnzyme(enzymeName)?.let {
                    digestPanel.selectEnzymes(digestPanel.selectedEnzymes() + it)
                }
            }
        }
    }

    private fun createCommonEnzymesMenu(): JMenu {
        return JMenu("Common Enzymes").apply {
            rebuildCommonEnzymes(this)
        }
    }

    private fun rebuildCommonEnzymes(menu: JMenu = commonEnzymesMenu) {
        val pool = prefs.value.enzymePool()
        menu.removeAll()
        val categories = pool.groupBy { it.name.first() }.toSortedMap()
        for ((letter, enzymes) in categories) {
            menu.add(JMenu("$letter").apply {
                for (enzyme in enzymes.take(10)) {
                    add(JMenuItem(enzyme.name).apply {
                        addActionListener { add(enzyme) }
                    })
                }
                if (enzymes.size > 10) {
                    add(JMenuItem("... and ${enzymes.size - 10} more").apply {
                        isEnabled = false
                        toolTipText = "Use Manage Enzymes to browse all ${enzymes.size} enzymes in this category."
                    })
                }
            })
        }
    }

    private fun add(enzyme: Enzyme) {
        digestPanel.selectEnzymes(digestPanel.selectedEnzymes() + enzyme)
    }

    private fun createAboutItem(): JMenuItem {
        return JMenuItem("About InstaGene").apply {
            addActionListener {
                JOptionPane.showMessageDialog(
                    null,
                    "InstaGene ${Version.VERSION}\n\nA gene editing tool.\n\nBuilt with Kotlin and Swing.",
                    "About InstaGene",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        }
    }
}
