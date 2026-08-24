package org.instagene.app.gui.dialog

import org.instagene.app.gui.monospacedTextArea
import org.instagene.app.gui.document.SeqDocument
import org.instagene.core.Enzyme
import org.instagene.core.EnzymeAnalysis
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JScrollPane

/** Small, reusable Swing front ends for engine analysis workflows. */
object AnalysisDialogs {
    fun showDiagnostic(frame: JFrame?, doc: SeqDocument, enzymes: List<Enzyme>) {
        if (enzymes.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Tick at least one enzyme first.", "Diagnostic Site", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val start = if (doc.hasSelection) doc.selectionStart else 0
        val end = if (doc.hasSelection) doc.selectionEnd - 1 else doc.seq.length - 1
        val candidates = EnzymeAnalysis.diagnosticSites(doc.seq, start..end, enzymes, 1)
        showDiagnosticText(frame, candidates.joinToString("\n") {
            "${it.position + 1}\t${it.enzyme.name}\t${it.original} -> ${it.mutated}\t${it.strand.symbol}"
        }.ifBlank { "No one-mismatch diagnostic sites found." })
    }

    private fun showDiagnosticText(frame: JFrame?, text: String) {
        val area = monospacedTextArea(24, 80, text)
        JOptionPane.showMessageDialog(frame, JScrollPane(area), "Diagnostic Sites", JOptionPane.INFORMATION_MESSAGE)
    }
}
