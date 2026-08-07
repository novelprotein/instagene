package org.instagene.app.gui

import java.awt.event.KeyEvent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.KeyStroke

class EditMenu(private val frame: javax.swing.JFrame?, private val doc: SeqDocument, private val sequenceView: SequenceView) {

    private var lastPattern: String? = null

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
            add(createFindNextItem())
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
                val pattern = javax.swing.JOptionPane.showInputDialog(frame, "Find sequence:", lastPattern ?: "")
                if (pattern != null && pattern.isNotEmpty()) {
                    lastPattern = pattern.uppercase()
                    findNext()
                }
            }
        }
    }

    private fun createFindNextItem(): JMenuItem {
        return JMenuItem("Find Next", KeyEvent.VK_F3).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0)
            addActionListener { findNext() }
        }
    }

    /**
     * Finds the next occurrence of the last pattern at or after the caret,
     * wrapping around. Nucleotide sequences are also searched on the reverse
     * strand, so a pattern given on one strand still matches its complement.
     */
    private fun findNext() {
        val pattern = lastPattern ?: return
        val bases = doc.seq.bases
        if (bases.isEmpty()) return
        val from = doc.caret.coerceIn(0, bases.length)
        val forward = wrapFind(bases, pattern, from)
        if (forward != null) {
            reveal(forward, pattern.length)
            return
        }
        if (doc.seq.kind != org.instagene.core.SeqKind.PROTEIN) {
            val rc = buildString(pattern.length) {
                for (i in pattern.indices.reversed()) append(org.instagene.core.Alphabet.complement(pattern[i], doc.seq.kind))
            }
            val reverse = wrapFind(bases, rc, from)
            if (reverse != null) {
                reveal(reverse, pattern.length)
                return
            }
        }
        javax.swing.JOptionPane.showMessageDialog(frame, "Pattern not found.", "Find", javax.swing.JOptionPane.INFORMATION_MESSAGE)
    }

    /** First index of [needle] at or after [from], wrapping to the start when not found. */
    private fun wrapFind(bases: String, needle: String, from: Int): Int? {
        val first = bases.indexOf(needle, from)
        if (first >= 0) return first
        val second = bases.indexOf(needle, 0)
        return if (second >= 0) second else null
    }

    private fun reveal(start: Int, length: Int) {
        doc.select(start, start + length)
        sequenceView.revealRange(start, start + length)
    }
}
