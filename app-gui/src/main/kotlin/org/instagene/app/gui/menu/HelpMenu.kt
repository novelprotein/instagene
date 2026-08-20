package org.instagene.app.gui.menu

import org.instagene.core.Version
import java.awt.event.KeyEvent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane

class HelpMenu {

    fun create(): JMenu {
        return JMenu("Help").apply {
            mnemonic = KeyEvent.VK_H
            add(createAboutItem())
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
