package org.instagene.app.gui.edit

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.tool.SequenceView
import org.instagene.app.gui.document.TextEditorView
import org.instagene.core.Alphabet
import org.instagene.core.SeqKind

/**
 * The editing operations an editor exposes to the Edit menu. Both the sequence
 * editor ([SequenceView]) and the plain-text editor ([TextEditorView]) provide
 * the same surface, so one Edit menu serves every open document kind.
 */
interface EditActions {
    fun undo()
    fun redo()
    fun canUndo(): Boolean
    fun canRedo(): Boolean
    fun undoLabel(): String?
    fun redoLabel(): String?
    fun selectAll()
    fun copySelection()
    fun cutSelection()
    fun paste()
    fun deleteSelection()
    fun hasSelection(): Boolean
    fun findNext(pattern: String, useRegex: Boolean = false): Boolean
}

/** [EditActions] for the sequence editor, bound to the document it was built for. */
class SequenceEditActions(
    private val view: SequenceView,
    private val doc: SeqDocument,
) : EditActions {

    override fun undo() {
        doc.undo()
    }

    override fun redo() {
        doc.redo()
    }

    override fun canUndo(): Boolean = doc.canUndo()

    override fun canRedo(): Boolean = doc.canRedo()

    override fun undoLabel(): String? = doc.undoLabel()

    override fun redoLabel(): String? = doc.redoLabel()

    override fun selectAll() {
        doc.selectAll()
    }

    override fun copySelection() {
        view.copySelection()
    }

    override fun cutSelection() {
        view.copySelection()
        view.deleteSelection()
    }

    override fun paste() {
        view.paste()
    }

    override fun deleteSelection() {
        view.deleteSelection()
    }

    override fun hasSelection(): Boolean = doc.hasSelection

    /**
     * Finds the next occurrence at or after the caret, wrapping at the end.
     * Nucleotide documents also search the reverse complement.
     */
    override fun findNext(pattern: String, useRegex: Boolean): Boolean {
        val bases = doc.seq.bases
        if (bases.isEmpty()) return false

        val needle = if (useRegex) {
            try {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                val match = regex.find(bases, doc.caret.coerceIn(0, bases.length))
                    ?: regex.find(bases, 0)
                if (match != null) {
                    view.revealRange(match.range.first, match.range.last + 1)
                    return true
                }
                return false
            } catch (_: Exception) {
                return false
            }
        } else {
            pattern.uppercase()
        }

        val from = doc.caret.coerceIn(0, bases.length)
        val forward = wrapFind(bases, needle, from)
        if (forward != null) {
            view.revealRange(forward, forward + needle.length)
            return true
        }
        if (doc.seq.kind != SeqKind.PROTEIN) {
            val rc = buildString(needle.length) {
                for (i in needle.indices.reversed()) {
                    append(Alphabet.complement(needle[i], doc.seq.kind))
                }
            }
            val reverse = wrapFind(bases, rc, from)
            if (reverse != null) {
                view.revealRange(reverse, reverse + rc.length)
                return true
            }
        }
        return false
    }

    private fun wrapFind(bases: String, needle: String, from: Int): Int? {
        val first = bases.indexOf(needle, from)
        if (first >= 0) return first
        val second = bases.indexOf(needle, 0)
        return if (second >= 0) second else null
    }
}

/** [EditActions] for the plain-text editor, driving the displayed [TextEditorView]. */
class TextEditActions(private val view: TextEditorView) : EditActions {

    override fun undo() {
        view.document.undo()
    }

    override fun redo() {
        view.document.redo()
    }

    override fun canUndo(): Boolean = view.document.canUndo()

    override fun canRedo(): Boolean = view.document.canRedo()

    override fun undoLabel(): String? = view.document.undoLabel()

    override fun redoLabel(): String? = view.document.redoLabel()

    override fun selectAll() {
        view.area.selectAll()
    }

    override fun copySelection() {
        view.area.copy()
    }

    override fun cutSelection() {
        view.area.cut()
    }

    override fun paste() {
        view.area.paste()
    }

    override fun deleteSelection() {
        view.area.replaceSelection("")
    }

    override fun hasSelection(): Boolean = view.area.selectionStart != view.area.selectionEnd

    /** Selects the next case-insensitive occurrence at or after the caret, wrapping around. */
    override fun findNext(pattern: String, useRegex: Boolean): Boolean {
        val text = view.area.text
        if (text.isEmpty()) return false
        val from = view.area.selectionEnd.coerceIn(0, text.length)

        if (useRegex) {
            try {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                val match = regex.find(text, from) ?: regex.find(text, 0)
                if (match != null) {
                    view.area.select(match.range.first, match.range.last + 1)
                    return true
                }
                return false
            } catch (_: Exception) {
                return false
            }
        }

        val first = text.indexOf(pattern, from, ignoreCase = true)
        if (first >= 0) {
            view.area.select(first, first + pattern.length)
            return true
        }
        val wrapped = text.indexOf(pattern, 0, ignoreCase = true)
        if (wrapped >= 0) {
            view.area.select(wrapped, wrapped + pattern.length)
            return true
        }
        return false
    }
}
