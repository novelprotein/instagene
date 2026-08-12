package org.instagene.app.gui.ui

import org.instagene.app.gui.enzyme.EnzymeManagerDialog
import org.instagene.app.gui.enzyme.enzymePool
import org.instagene.app.gui.enzyme.findEnzyme
import org.instagene.app.gui.file.Prefs
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
) {

    private val makeCircularItem = JMenuItem("Make Circular")
    private val makeLinearItem = JMenuItem("Make Linear")
    private val addEnzymeItem = JMenuItem("Add Enzyme...")
    private val clearEnzymesItem = JMenuItem("Clear All Enzymes")
    private val manageEnzymesItem = JMenuItem("Manage Enzymes...")
    private val commonEnzymesMenu = createCommonEnzymesMenu()

    init {
        doc.addListener { _, reason ->
            if (reason == SeqDocument.Reason.SEQUENCE) syncEnabled()
        }
        prefs.addListener { rebuildCommonEnzymes() }
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
        makeCircularItem.isEnabled = nucleotide && !seq.isCircular
        makeLinearItem.isEnabled = nucleotide && seq.isCircular
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
            addSeparator()
            add(createAboutItem())
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
                    add(JMenuItem("... and ${enzymes.size - 10} more"))
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
