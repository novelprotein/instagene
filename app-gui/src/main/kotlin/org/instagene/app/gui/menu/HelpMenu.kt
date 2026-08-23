package org.instagene.app.gui.menu

import org.instagene.core.Version
import java.awt.event.KeyEvent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JTextArea

class HelpMenu {

    fun create(): JMenu {
        return JMenu("Help").apply {
            mnemonic = KeyEvent.VK_H
            add(createFeatureGuideItem())
            addSeparator()
            add(createAboutItem())
        }
    }

    private fun createFeatureGuideItem(): JMenuItem = JMenuItem("Feature annotation guide...").apply {
        addActionListener {
            val guide = JTextArea(
                """
                Feature annotation guide

                1. Select bases in Sequence, then use Tools > Features > Add Feature from Selection.
                   Use Add Feature Manually when the coordinates are already known.

                2. Give the feature a useful name and type, verify its one-based displayed
                   coordinates and strand, then add notes or qualifiers needed downstream.

                3. Use Edit Element to adjust the display color, visibility, order, translation
                   settings, and descriptions. Save reusable annotations to the Feature Library.

                4. Tools > Features > Auto-annotate searches the sequence using bundled or
                   saved definitions. Choose both strands when appropriate and review results
                   before saving the record.

                5. Save annotated or circular records as GenBank. FASTA does not preserve
                   features, qualifiers, colors, or circular topology.
                """.trimIndent(),
            18,
            68,
            ).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                caretPosition = 0
            }
            JOptionPane.showMessageDialog(null, JScrollPane(guide), "Feature annotation guide", JOptionPane.INFORMATION_MESSAGE)
        }
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
