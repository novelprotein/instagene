package org.instagene.app.gui

import java.awt.event.KeyEvent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.KeyStroke

class ViewMenu(private val sequenceView: SequenceView) {

    fun create(): JMenu {
        return JMenu("View").apply {
            mnemonic = KeyEvent.VK_V

            add(createShowComplementItem())
            add(createShowTranslationItem())
            addSeparator()
            add(createZoomInItem())
            add(createZoomOutItem())
            add(createResetZoomItem())
        }
    }

    private fun createShowComplementItem(): JMenuItem {
        return JMenuItem("Show Complement Strand").apply {
            addActionListener {
                sequenceView.showComplement = !sequenceView.showComplement
                text = if (sequenceView.showComplement) "Hide Complement Strand" else "Show Complement Strand"
            }
        }
    }

    private fun createShowTranslationItem(): JMenuItem {
        return JMenuItem("Show Translation").apply {
            addActionListener {
                sequenceView.showTranslation = !sequenceView.showTranslation
                text = if (sequenceView.showTranslation) "Hide Translation" else "Show Translation"
            }
        }
    }

    private fun createZoomInItem(): JMenuItem {
        return JMenuItem("Zoom In", KeyEvent.VK_PLUS).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener {
                val current = sequenceView.fontSize()
                sequenceView.setFontSize(current + 1)
            }
        }
    }

    private fun createZoomOutItem(): JMenuItem {
        return JMenuItem("Zoom Out", KeyEvent.VK_MINUS).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener {
                val current = sequenceView.fontSize()
                sequenceView.setFontSize(current - 1)
            }
        }
    }

    private fun createResetZoomItem(): JMenuItem {
        return JMenuItem("Reset Zoom", KeyEvent.VK_0).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_0, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener {
                sequenceView.setFontSize(14)
            }
        }
    }
}
