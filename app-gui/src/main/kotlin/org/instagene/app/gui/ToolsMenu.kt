package org.instagene.app.gui

import org.instagene.core.Enzyme
import org.instagene.core.Enzymes
import org.instagene.core.SeqKind
import org.instagene.core.Topology
import java.awt.event.KeyEvent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane

class ToolsMenu(private val doc: SeqDocument, private val digestPanel: DigestPanel) {

    private val makeCircularItem = JMenuItem("Make Circular")
    private val makeLinearItem = JMenuItem("Make Linear")
    private val addEnzymeItem = JMenuItem("Add Enzyme...")
    private val clearEnzymesItem = JMenuItem("Clear All Enzymes")
    private val commonEnzymesMenu = createCommonEnzymesMenu()

    init {
        doc.addListener { _, reason ->
            if (reason == SeqDocument.Reason.SEQUENCE) syncEnabled()
        }
        syncEnabled()
    }

    /** Digestion and topology only apply to nucleotide sequences; follow the sample type. */
    private fun syncEnabled() {
        val seq = doc.seq
        val dna = seq.kind == SeqKind.DNA
        val nucleotide = seq.kind != SeqKind.PROTEIN
        addEnzymeItem.isEnabled = dna
        clearEnzymesItem.isEnabled = dna
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
                addActionListener {
                    val enzymeName = JOptionPane.showInputDialog(null, "Enzyme name:", "BamHI")
                    if (enzymeName != null && enzymeName.isNotEmpty()) {
                        val enzyme = Enzymes.ALL.firstOrNull { it.name.equals(enzymeName, ignoreCase = true) }
                        if (enzyme != null) {
                            digestPanel.selectEnzymes(digestPanel.selectedEnzymes() + enzyme)
                        } else {
                            JOptionPane.showMessageDialog(null, "Enzyme '$enzymeName' not found.", "Not Found", JOptionPane.WARNING_MESSAGE)
                        }
                    }
                }
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

    private fun createCommonEnzymesMenu(): JMenu {
        return JMenu("Common Enzymes").apply {
            val categories = Enzymes.ALL.groupBy { it.name.first() }.toSortedMap()
            for ((letter, enzymes) in categories) {
                add(JMenu("$letter").apply {
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
    }

    private fun add(enzyme: Enzyme) {
        digestPanel.selectEnzymes(digestPanel.selectedEnzymes() + enzyme)
    }

    private fun createAboutItem(): JMenuItem {
        return JMenuItem("About InstaGene").apply {
            addActionListener {
                JOptionPane.showMessageDialog(
                    null,
                    "InstaGene v0.1.0-alpha\n\nA vibe-coded gene editing tool.\n\nBuilt with Kotlin and Swing.",
                    "About InstaGene",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        }
    }
}
