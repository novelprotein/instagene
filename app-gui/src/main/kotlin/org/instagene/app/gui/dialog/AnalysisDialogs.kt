package org.instagene.app.gui.dialog

import org.instagene.app.gui.ContextMenus
import org.instagene.app.gui.document.SeqDocument
import org.instagene.core.*
import org.instagene.core.io.SeqIO
import javax.swing.*

/** Small, reusable Swing front ends for engine analysis workflows. */
object AnalysisDialogs {
    fun showIdentity(frame: JFrame?, doc: SeqDocument) {
        val id = SequenceIdentity.cdseguid(doc.seq)
        val state = if (doc.seq.uniqueIdentifier == null) "not applied" else "verified=${SequenceIdentity.verify(doc.seq)}"
        val choice = JOptionPane.showOptionDialog(
            frame,
            "$id\nStatus: $state",
            "Sequence Identity",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.INFORMATION_MESSAGE,
            null,
            arrayOf("Copy", "Apply", "Close"),
            "Close",
        )
        when (choice) {
            0 -> ContextMenus.copyToClipboard(id)
            1 -> doc.mutate("apply sequence identity") { it.withUniqueIdentifier(id) }
        }
    }

    fun showAlignment(frame: JFrame?, doc: SeqDocument) {
        val chooser = JFileChooser().apply { dialogTitle = "Choose query sequence" }
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return
        val query = try { SeqIO.read(chooser.selectedFile) } catch (e: Exception) {
            JOptionPane.showMessageDialog(frame, e.message ?: "Unable to read query", "Alignment", JOptionPane.ERROR_MESSAGE)
            return
        }
        val result = Alignment.align(doc.seq, listOf(query), AlignmentParameters())
        val match = result.queries.single()
        val text = buildString {
            append("Reference: ${doc.seq.name}\nQuery: ${query.name}\n")
            append("Score: ${"%.2f".format(match.score)}  Matches: ${match.matches}  Mismatches: ${match.mismatches}  Gaps: ${match.gaps}\n\n")
            result.reference.sequence.chunked(60).forEachIndexed { index, block ->
                val from = index * 60
                append("REF ${from + 1}: $block\n")
                append("    ").append(match.sequence.drop(from).take(block.length)).append("\n\n")
            }
        }
        showText(frame, "Alignment", text)
    }

    fun showGel(frame: JFrame?, doc: SeqDocument) {
        val names = JOptionPane.showInputDialog(frame, "Enzymes (comma-separated):", "EcoRI") ?: return
        val enzymes = try { Enzymes.parseList(names) } catch (e: Exception) {
            JOptionPane.showMessageDialog(frame, e.message ?: "Unknown enzyme", "Virtual Gel", JOptionPane.ERROR_MESSAGE)
            return
        }
        val completion = JOptionPane.showInputDialog(frame, "Digest completion percentage:", "100")?.toIntOrNull() ?: 100
        val result = VirtualGel.run(listOf(GelLane.Dna(doc.seq.name, doc.seq, enzymes, completion)))
        val text = buildString {
            append("${doc.seq.name} virtual digest\n\n")
            result.lanes.single().bands.forEach { band ->
                append("${band.sizeBp} bp\tintensity ${"%.1f".format(band.relativeIntensity)}\tmigration ${"%.3f".format(result.migration(band.sizeBp))}\n")
            }
        }
        showText(frame, "Virtual Gel", text)
    }

    fun showDiagnostic(frame: JFrame?, doc: SeqDocument, enzymes: List<Enzyme>) {
        if (enzymes.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Tick at least one enzyme first.", "Diagnostic Site", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val start = if (doc.hasSelection) doc.selectionStart else 0
        val end = if (doc.hasSelection) doc.selectionEnd - 1 else doc.seq.length - 1
        val candidates = EnzymeAnalysis.diagnosticSites(doc.seq, start..end, enzymes, 1)
        showText(frame, "Diagnostic Sites", candidates.joinToString("\n") {
            "${it.position + 1}\t${it.enzyme.name}\t${it.original} -> ${it.mutated}\t${it.strand.symbol}"
        }.ifBlank { "No one-mismatch diagnostic sites found." })
    }

    fun showMolecularCalculator(frame: JFrame?) {
        val stock = JOptionPane.showInputDialog(frame, "Stock concentration:", "100")?.toDoubleOrNull() ?: return
        val final = JOptionPane.showInputDialog(frame, "Final concentration:", "10")?.toDoubleOrNull() ?: return
        val volume = JOptionPane.showInputDialog(frame, "Final volume (µl):", "100")?.toDoubleOrNull() ?: return
        val result = MolecularCalculators.dilution(stock, final, volume)
        showText(frame, "Molecular Calculator", "Stock: %.2f µl\nDiluent: %.2f µl\nFinal volume: %.2f µl".format(result.stockVolumeUl, result.diluentVolumeUl, result.finalVolumeUl))
    }

    private fun showText(frame: JFrame?, title: String, text: String) {
        val area = org.instagene.app.gui.monospacedTextArea(24, 80, text)
        JOptionPane.showMessageDialog(frame, JScrollPane(area), title, JOptionPane.INFORMATION_MESSAGE)
    }
}
