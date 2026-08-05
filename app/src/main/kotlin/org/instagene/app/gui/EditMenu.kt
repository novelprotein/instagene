package org.instagene.app.gui

import java.awt.event.KeyEvent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.KeyStroke

class EditMenu(private val frame: javax.swing.JFrame, private val doc: SeqDocument, private val sequenceView: SequenceView) {

    fun create(): JMenu {
        return JMenu("Edit").apply {
            mnemonic = KeyEvent.VK_E

            add(createUndoItem())
            add(createRedoItem())
            addSeparator()
            add(createSelectAllItem())
            addSeparator()
            add(createCopyItem())
            add(createPasteItem())
            add(createCutItem())
            add(createDeleteItem())
            addSeparator()
            add(createFindItem())
        }
    }

    private fun createUndoItem(): JMenuItem {
        return JMenuItem("Undo", KeyEvent.VK_Z).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener { doc.undo() }
        }
    }

    private fun createRedoItem(): JMenuItem {
        return JMenuItem("Redo", KeyEvent.VK_Y).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener { doc.redo() }
        }
    }

    private fun createSelectAllItem(): JMenuItem {
        return JMenuItem("Select All", KeyEvent.VK_A).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_A, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener { doc.selectAll() }
        }
    }

    private fun createCopyItem(): JMenuItem {
        return JMenuItem("Copy", KeyEvent.VK_C).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_C, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener { sequenceView.copySelection() }
        }
    }

    private fun createPasteItem(): JMenuItem {
        return JMenuItem("Paste", KeyEvent.VK_V).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener { sequenceView.paste() }
        }
    }

    private fun createCutItem(): JMenuItem {
        return JMenuItem("Cut", KeyEvent.VK_X).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_X, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener {
                sequenceView.copySelection()
                sequenceView.deleteSelection()
            }
        }
    }

    private fun createDeleteItem(): JMenuItem {
        return JMenuItem("Delete", KeyEvent.VK_D).apply {
            addActionListener { sequenceView.deleteSelection() }
        }
    }

    private fun createFindItem(): JMenuItem {
        return JMenuItem("Find...", KeyEvent.VK_F).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK)
            addActionListener {
                val pattern = javax.swing.JOptionPane.showInputDialog(frame, "Find sequence:")
                if (pattern != null && pattern.isNotEmpty()) {
                    val index = doc.seq.bases.indexOf(pattern.uppercase())
                    if (index >= 0) {
                        sequenceView.revealRange(index, index + pattern.length)
                    } else {
                        javax.swing.JOptionPane.showMessageDialog(frame, "Pattern not found.", "Find", javax.swing.JOptionPane.INFORMATION_MESSAGE)
                    }
                }
            }
        }
    }
}
