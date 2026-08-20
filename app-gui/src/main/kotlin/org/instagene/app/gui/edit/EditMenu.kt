package org.instagene.app.gui.edit

import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.document.Doc
import org.instagene.app.gui.menu.menuShortcut
import org.instagene.app.gui.tool.FeaturesPanel
import org.instagene.app.gui.prefs.SavedContext
import org.instagene.app.gui.prefs.SavedItem
import org.instagene.app.gui.prefs.SavedKind
import org.instagene.core.SeqKind
import java.awt.event.KeyEvent
import javax.swing.JFrame
import javax.swing.JCheckBox
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke

class EditMenu(
    private val frame: JFrame?,
    private val doc: Doc,
    private val editor: EditActions,
    private val prefs: Prefs = Prefs(),
    private val featuresPanel: FeaturesPanel? = null,
    private val sequenceView: org.instagene.app.gui.tool.SequenceView? = null,
    private val onEditProperties: (() -> Unit)? = null,
) {

    private val undoItem = JMenuItem("Undo", KeyEvent.VK_Z).apply {
        accelerator = menuShortcut(KeyEvent.VK_Z)
        addActionListener { editor.undo() }
    }

    private val redoItem = JMenuItem("Redo", KeyEvent.VK_Y).apply {
        accelerator = menuShortcut(KeyEvent.VK_Y)
        addActionListener { editor.redo() }
    }

    /** Annotates the current selection as a feature (sequence documents only). */
    private val addFeatureItem = JMenuItem("Add Feature from Selection...").apply {
        addActionListener { featuresPanel?.addFeatureDialog() }
    }

    /** Jumps to the sequence's editable name/properties on the Info tab. */
    private val editPropertiesItem = JMenuItem("Edit Name / Properties...").apply {
        addActionListener { onEditProperties?.invoke() }
    }

    private var lastPattern: String? = null
    private var lastUseRegex = false

    init {
        doc.addDocListener {
            refreshUndoRedo()
            refreshFeatureItems()
        }
        refreshUndoRedo()
        refreshFeatureItems()
    }

    /** Keeps the Undo/Redo items' labels and enabled state in step with the document history. */
    private fun refreshUndoRedo() {
        undoItem.isEnabled = editor.canUndo()
        redoItem.isEnabled = editor.canRedo()
        undoItem.text = editor.undoLabel()?.let { "Undo $it" } ?: "Undo"
        redoItem.text = editor.redoLabel()?.let { "Redo $it" } ?: "Redo"
    }

    /** Keeps the sequence-only items in step with the current selection. */
    private fun refreshFeatureItems() {
        val seqDoc = doc as? SeqDocument ?: return
        addFeatureItem.isEnabled = seqDoc.hasSelection && seqDoc.selectionEnd > seqDoc.selectionStart
        editPropertiesItem.isEnabled = true
    }

    fun create(): JMenu {
        return JMenu("Edit").apply {
            mnemonic = KeyEvent.VK_E

            add(undoItem)
            add(redoItem)
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
            add(createGoToItem())
            addSeparator()
            if (doc is SeqDocument) {
                add(createSaveSelectionItem())
                addSeparator()
                add(addFeatureItem)
                add(editPropertiesItem)
            }
        }
    }

    private fun createSelectAllItem(): JMenuItem {
        return JMenuItem("Select All", KeyEvent.VK_A).apply {
            accelerator = menuShortcut(KeyEvent.VK_A)
            addActionListener { editor.selectAll() }
        }
    }

    private fun createCopyItem(): JMenuItem {
        return JMenuItem("Copy", KeyEvent.VK_C).apply {
            accelerator = menuShortcut(KeyEvent.VK_C)
            addActionListener { editor.copySelection() }
        }
    }

    private fun createPasteItem(): JMenuItem {
        return JMenuItem("Paste", KeyEvent.VK_V).apply {
            accelerator = menuShortcut(KeyEvent.VK_V)
            addActionListener { editor.paste() }
        }
    }

    private fun createCutItem(): JMenuItem {
        return JMenuItem("Cut", KeyEvent.VK_X).apply {
            accelerator = menuShortcut(KeyEvent.VK_X)
            addActionListener { editor.cutSelection() }
        }
    }

    private fun createDeleteItem(): JMenuItem {
        return JMenuItem("Delete", KeyEvent.VK_D).apply {
            addActionListener { editor.deleteSelection() }
        }
    }

    private fun createFindItem(): JMenuItem {
        return JMenuItem("Find...", KeyEvent.VK_F).apply {
            accelerator = menuShortcut(KeyEvent.VK_F)
            addActionListener {
                val panel = JPanel(java.awt.BorderLayout(4, 4))
                val inputField = JTextField(lastPattern ?: "", 30)
                val regexCheckBox = JCheckBox("Regex", lastUseRegex)
                panel.add(inputField, java.awt.BorderLayout.CENTER)
                panel.add(regexCheckBox, java.awt.BorderLayout.EAST)
                val result = JOptionPane.showConfirmDialog(
                    frame, panel, "Find", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
                )
                if (result == JOptionPane.OK_OPTION) {
                    val pattern = inputField.text
                    if (pattern.isNotEmpty()) {
                        lastPattern = pattern
                        lastUseRegex = regexCheckBox.isSelected
                        findNext()
                    }
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

    private fun createGoToItem(): JMenuItem {
        return JMenuItem("Go to Position...", KeyEvent.VK_G).apply {
            accelerator = menuShortcut(KeyEvent.VK_G)
            addActionListener { goToPosition() }
        }
    }

    private fun goToPosition() {
        val seqDoc = doc as? SeqDocument ?: return
        val input = JOptionPane.showInputDialog(
            frame,
            "Go to base position (1-based):",
            "Go to Position",
            JOptionPane.QUESTION_MESSAGE,
        )
        if (input.isNullOrBlank()) return
        val pos = input.toIntOrNull()
        if (pos == null || pos < 1 || pos > seqDoc.seq.length) {
            JOptionPane.showMessageDialog(
                frame,
                "Position must be between 1 and ${seqDoc.seq.length}.",
                "Invalid Position",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        // Find the SequenceView through the editor
        sequenceView?.revealRange(pos - 1, pos)
    }

    /** Stores the current selection in the library, tagged with its source range. */
    private fun createSaveSelectionItem(): JMenuItem {
        return JMenuItem("Save Selection to Library...").apply {
            isEnabled = (doc as? SeqDocument)?.seq?.kind != SeqKind.PROTEIN
            addActionListener { saveSelectionToLibrary() }
        }
    }

    private fun saveSelectionToLibrary() {
        if (doc !is SeqDocument) return
        if (doc.seq.kind == SeqKind.PROTEIN) return
        if (!doc.hasSelection || doc.selectionEnd <= doc.selectionStart) {
            JOptionPane.showMessageDialog(frame, "Select a region to save first.")
            return
        }
        val start = doc.selectionStart
        val end = doc.selectionEnd
        val item = SavedItem(
            kind = SavedKind.FRAGMENT,
            name = "${doc.seq.name}_${start + 1}-$end",
            bases = doc.selectedBases,
            context = SavedContext(
                sourceName = doc.seq.name,
                start = start,
                end = end,
                enzymes = doc.mappedEnzymes.map { it.name },
            ),
            sequenceKind = doc.seq.kind,
        )
        prefs.update { it.copy(library = it.library + item) }
    }

    /** Runs the editor's find; shows a dialog when nothing matches. */
    private fun findNext() {
        val pattern = lastPattern ?: return
        if (!editor.findNext(pattern, lastUseRegex)) {
            JOptionPane.showMessageDialog(frame, "Pattern not found.", "Find", JOptionPane.INFORMATION_MESSAGE)
        }
    }
}
