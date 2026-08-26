package org.instagene.app.gui.edit

import org.instagene.app.gui.document.SeqDocument
import org.instagene.core.Alphabet
import org.instagene.core.SeqKind

/** Shared, validated sequence-edit commands used by menus, keyboard input, and quick actions. */
object SequenceEditService {
    fun clean(doc: SeqDocument, text: String): String = when (doc.seq.kind) {
        SeqKind.PROTEIN -> Alphabet.clean(text).uppercase().filter { Alphabet.isAminoAcid(it) && it != '-' && it != '*' }
        else -> Alphabet.clean(text).uppercase().filter { Alphabet.isNucleotide(it) && it != '-' }
    }

    fun insert(doc: SeqDocument, text: String): Boolean {
        val clean = clean(doc, text)
        if (clean.isEmpty()) return false
        val start = doc.selectionStart
        if (doc.hasSelection) {
            val end = doc.selectionEnd
            doc.mutate("replace ${end - start} bases") { it.replaceRange(start, end, clean) }
        } else {
            doc.mutate("insert ${clean.length} bases") { it.insertAt(start, clean) }
        }
        doc.moveCaret(start + clean.length)
        return true
    }

    fun deleteSelection(doc: SeqDocument): Boolean {
        if (!doc.hasSelection) return false
        val start = doc.selectionStart
        val end = doc.selectionEnd
        doc.mutate("delete ${end - start} bases") { it.deleteRange(start, end) }
        doc.moveCaret(start)
        return true
    }

    fun trimSelection(doc: SeqDocument): Boolean {
        if (!doc.hasSelection) return false
        val start = doc.selectionStart
        val end = doc.selectionEnd
        doc.mutate("trim selection") { it.deleteRange(start, end) }
        doc.moveCaret(start)
        return true
    }

    fun extractSelection(doc: SeqDocument): org.instagene.core.Seq? {
        if (!doc.hasSelection) return null
        val start = doc.selectionStart
        val end = doc.selectionEnd
        return doc.seq.subSeq(start, end)
    }
}
