package org.instagene.app.gui

import org.instagene.core.SeqKind
import java.awt.event.KeyEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.KeyStroke

class ViewMenu(
    private val doc: SeqDocument,
    private val sequenceView: SequenceView,
) {

    private val complementItem = JCheckBoxMenuItem("Show Complement Strand", false)
    private val translationItem = JCheckBoxMenuItem("Show Translation", false)

    init {
        doc.addListener { _, reason ->
            if (reason == SeqDocument.Reason.SEQUENCE) updateEnabled()
        }
        updateEnabled()
    }

    /** Complement and translation tracks only exist for DNA/RNA sequences. */
    private fun updateEnabled() {
        val nucleotide = doc.seq.kind != SeqKind.PROTEIN
        complementItem.isEnabled = nucleotide
        translationItem.isEnabled = nucleotide
    }

    fun create(): JMenu {
        return JMenu("View").apply {
            mnemonic = KeyEvent.VK_V

            add(complementItem.apply {
                addActionListener {
                    sequenceView.showComplement = isSelected
                }
            })
            add(translationItem.apply {
                addActionListener {
                    sequenceView.showTranslation = isSelected
                }
            })
            addSeparator()
            add(createZoomInItem())
            add(createZoomOutItem())
            add(createResetZoomItem())
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
