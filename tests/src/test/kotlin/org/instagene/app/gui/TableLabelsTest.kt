package org.instagene.app.gui

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.edit.EditHistoryPanel
import org.instagene.app.gui.edit.EditRecorder
import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.tool.DigestPanel
import org.instagene.app.gui.tool.FeaturesPanel
import org.instagene.app.gui.tool.LibraryPanel
import org.instagene.app.gui.tool.PrimersPanel
import org.instagene.app.gui.tool.SequenceView
import org.instagene.core.Feature
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import java.awt.Component
import java.awt.Container
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableLabelsTest {

    @Test
    fun allToolTablesUseConsistentHeadersAndUnits() = onEdt {
        val doc = SeqDocument(
            Seq(
                name = "table-test",
                bases = "ACGTACGTACGTACGTACGTACGT",
                features = listOf(Feature("feature", start = 1, end = 5)),
            )
        )
        val view = SequenceView(doc)
        val prefs = Prefs()
        val digest = DigestPanel(doc, { _: Seq -> }, { _, _ -> }, prefs)
        val features = FeaturesPanel(doc) { _, _ -> }
        val primers = PrimersPanel(doc, prefs)
        val library = LibraryPanel(prefs, doc, view) { _ -> }
        val history = EditHistoryPanel(EditRecorder())

        assertEquals(listOf("Use", "Enzyme", "Recognition site", "Cut count", "Description"),
            table(digest, 0).headers())
        assertEquals(listOf("Length", "Start", "End", "Strand", "Overhang", "Recognition sequence", "Cut type"),
            table(digest, 1).headers())
        assertEquals(listOf("Name", "Type", "Start", "End", "Strand", "Length", "Description"),
            table(features).headers())
        assertEquals(listOf("Name", "Sequence", "Length", "Melting temperature", "GC content", "Description"),
            table(primers).headers())
        assertEquals(listOf("Kind", "Name", "Length", "Source", "Description"), library.libraryTable.headers())
        assertEquals(listOf("Time", "Document", "Change"), history.table.headers())

        assertEquals("4 bp", table(features).getValueAt(0, 5))
        assertTrue(table(primers).getValueAt(0, 2).toString().endsWith("nt"))
        assertTrue(table(primers).getValueAt(0, 3).toString().endsWith("°C"))
        assertTrue(table(primers).getValueAt(0, 4).toString().endsWith("%"))

        digest.dispose()
    }

    @Test
    fun libraryLengthUsesTheMoleculeUnit() = onEdt {
        val prefs = Prefs()
        val doc = SeqDocument(Seq(bases = "ACGT"))
        val panel = LibraryPanel(prefs, doc, SequenceView(doc)) { _ -> }

        panel.addLibraryItem(org.instagene.app.gui.prefs.SavedKind.FRAGMENT, "dna", SeqKind.DNA, "ACGT")
        panel.addLibraryItem(org.instagene.app.gui.prefs.SavedKind.FRAGMENT, "rna", SeqKind.RNA, "ACGU")

        assertEquals("4 bp", panel.libraryTable.model.getValueAt(0, 2))
        assertEquals("4 nt", panel.libraryTable.model.getValueAt(1, 2))
    }

    private fun table(root: Component, index: Int = 0): JTable = descendants(root, JTable::class.java)[index]

    private fun JTable.headers(): List<String> = (0 until columnCount).map(::getColumnName)

    private fun <T : Component> descendants(root: Component, type: Class<T>): List<T> {
        val found = ArrayList<T>()
        fun visit(component: Component) {
            if (type.isInstance(component)) found += type.cast(component)
            if (component is Container) component.components.forEach(::visit)
        }
        visit(root)
        return found
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
