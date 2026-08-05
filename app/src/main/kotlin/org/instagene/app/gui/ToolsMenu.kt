package org.instagene.app.gui

import org.instagene.core.Enzymes
import org.instagene.core.Enzyme
import java.awt.event.KeyEvent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane

class ToolsMenu(private val doc: SeqDocument, private val digestPanel: DigestPanel) {

    fun create(): JMenu {
        return JMenu("Tools").apply {
            mnemonic = KeyEvent.VK_T

            add(createAddEnzymeItem())
            add(createClearEnzymesItem())
            addSeparator()
            add(createCommonEnzymesMenu())
            addSeparator()
            add(createAboutItem())
        }
    }

    private fun createAddEnzymeItem(): JMenuItem {
        return JMenuItem("Add Enzyme...").apply {
            addActionListener {
                val enzymeName = JOptionPane.showInputDialog(null, "Enzyme name:", "BamHI")
                if (enzymeName != null && enzymeName.isNotEmpty()) {
                    val enzyme = Enzymes.ALL.firstOrNull { it.name.equals(enzymeName, ignoreCase = true) }
                    if (enzyme != null) {
                        doc.addEnzyme(enzyme)
                    } else {
                        JOptionPane.showMessageDialog(null, "Enzyme '$enzymeName' not found.", "Not Found", JOptionPane.WARNING_MESSAGE)
                    }
                }
            }
        }
    }

    private fun createClearEnzymesItem(): JMenuItem {
        return JMenuItem("Clear All Enzymes").apply {
            addActionListener { doc.clearEnzymes() }
        }
    }

    private fun createCommonEnzymesMenu(): JMenu {
        return JMenu("Common Enzymes").apply {
            val categories = Enzymes.ALL.groupBy { it.name.first() }.toSortedMap()
            for ((letter, enzymes) in categories) {
                add(JMenu("$letter").apply {
                    for (enzyme in enzymes.take(10)) {
                        add(JMenuItem(enzyme.name).apply {
                            addActionListener { doc.addEnzyme(enzyme) }
                        })
                    }
                    if (enzymes.size > 10) {
                        add(JMenuItem("... and ${enzymes.size - 10} more"))
                    }
                })
            }
        }
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
