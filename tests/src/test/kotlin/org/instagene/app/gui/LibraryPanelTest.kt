package org.instagene.app.gui

import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.prefs.SavedFeatureMetadata
import org.instagene.app.gui.prefs.SavedContext
import org.instagene.app.gui.prefs.SavedItem
import org.instagene.app.gui.prefs.SavedKind
import org.instagene.app.gui.tool.LibraryPanel
import org.instagene.app.gui.tool.SequenceView
import org.instagene.core.Seq
import org.instagene.core.SeqKind
import org.instagene.core.Strand
import org.instagene.core.Topology
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Regression coverage for creating and reusing all three Library item kinds. */
class LibraryPanelTest {

    @Test
    fun addItemAffordanceCreatesAllKindsAndRejectsInvalidInputWithoutMutation() = onEdt {
        val prefs = Prefs()
        val doc = SeqDocument(Seq(bases = "ACGT"))
        val panel = LibraryPanel(prefs, doc, SequenceView(doc)) { _ -> }

        assertTrue(panel.button("Add Item…").isVisible)

        assertNull(
            panel.addLibraryItem(
                kind = SavedKind.PRIMER,
                name = "  forward primer  ",
                sequenceKind = SeqKind.DNA,
                bases = "a u 1 c",
                description = "PCR primer",
            )
        )
        assertNull(
            panel.addLibraryItem(
                kind = SavedKind.FRAGMENT,
                name = "RNA insert",
                sequenceKind = SeqKind.RNA,
                bases = "a t2g",
            )
        )
        assertNull(
            panel.addLibraryItem(
                kind = SavedKind.FEATURE,
                name = "promoter",
                sequenceKind = SeqKind.RNA,
                bases = "tt aa",
                description = "Inducible promoter",
                featureType = "promoter",
                strand = Strand.REVERSE,
            )
        )

        val saved = prefs.value.library
        assertEquals(listOf(SavedKind.PRIMER, SavedKind.FRAGMENT, SavedKind.FEATURE), saved.map { it.kind })
        assertEquals(listOf("ATC", "AUG", "UUAA"), saved.map { it.bases })
        assertEquals(listOf(SeqKind.DNA, SeqKind.RNA, SeqKind.RNA), saved.map { it.sequenceKind })
        assertTrue(saved.all { it.context.sourceName.isBlank() })
        assertNull(saved[0].feature)
        assertNull(saved[1].feature)
        assertEquals("promoter", saved[2].feature?.type)
        assertEquals(Strand.REVERSE, saved[2].feature?.strand)
        assertEquals("Inducible promoter", saved[2].description)
        assertEquals(listOf("—", "—", "—"), (0..2).map { panel.libraryTable.model.getValueAt(it, 3) })

        val beforeInvalidAdds = prefs.value
        assertNotNull(panel.addLibraryItem(SavedKind.PRIMER, "", SeqKind.DNA, "ACGT"))
        assertNotNull(panel.addLibraryItem(SavedKind.FRAGMENT, "bad", SeqKind.DNA, "AC?T"))
        assertNotNull(panel.addLibraryItem(SavedKind.FEATURE, "protein", SeqKind.PROTEIN, "MEEK"))
        assertEquals(beforeInvalidAdds, prefs.value, "failed validation must not mutate preferences")
    }

    @Test
    fun featureOpenUsesAnAnnotatedLinearSequenceWithAllMetadata() = onEdt {
        val prefs = Prefs()
        val doc = SeqDocument(Seq(bases = "AAAA"))
        var opened: Seq? = null
        val panel = LibraryPanel(prefs, doc, SequenceView(doc)) { opened = it }
        panel.addItem(
            SavedItem(
                kind = SavedKind.FEATURE,
                name = "reverse gene",
                bases = "ATGC",
                description = "A saved coding region",
                sequenceKind = SeqKind.RNA,
                feature = SavedFeatureMetadata(
                    type = "CDS",
                    strand = Strand.REVERSE,
                    qualifiers = mapOf("gene" to listOf("revA"), "pseudo" to listOf("")),
                ),
            )
        )
        panel.libraryTable.setRowSelectionInterval(0, 0)

        panel.button("Open as sequence").doClick()

        val sequence = opened ?: fail("Open as sequence did not invoke its callback")
        assertEquals("reverse gene", sequence.name)
        assertEquals("AUGC", sequence.bases)
        assertEquals(SeqKind.RNA, sequence.kind)
        assertEquals(Topology.LINEAR, sequence.topology)
        assertEquals("A saved coding region", sequence.description)
        val feature = sequence.features.single()
        assertEquals("reverse gene", feature.name)
        assertEquals("CDS", feature.type)
        assertEquals(0, feature.start)
        assertEquals(4, feature.end)
        assertEquals(Strand.REVERSE, feature.strand)
        assertEquals("A saved coding region", feature.notes)
        assertEquals(mapOf("gene" to listOf("revA"), "pseudo" to listOf("")), feature.qualifiers)
    }

    @Test
    fun featureInsertReplacesSelectionAndAddsOffsetAnnotationInOneUndoStep() = onEdt {
        val original = Seq(name = "target", bases = "AAAACCCC")
        val doc = SeqDocument(original)
        val prefs = Prefs()
        val panel = LibraryPanel(prefs, doc, SequenceView(doc)) { _ -> }
        panel.addItem(
            SavedItem(
                kind = SavedKind.FEATURE,
                name = "RNA feature",
                bases = "AUG",
                description = "Inserted annotation",
                sequenceKind = SeqKind.RNA,
                feature = SavedFeatureMetadata(
                    type = "regulatory",
                    strand = Strand.REVERSE,
                    qualifiers = mapOf("regulatory_class" to listOf("promoter")),
                ),
            )
        )
        panel.libraryTable.setRowSelectionInterval(0, 0)
        doc.select(2, 6)

        panel.button("Insert at caret").doClick()

        assertEquals("AAATGCC", doc.seq.bases, "RNA U must be normalized to T in the DNA target")
        assertEquals(5, doc.caret)
        assertFalse(doc.hasSelection)
        val feature = doc.seq.features.single()
        assertEquals("RNA feature", feature.name)
        assertEquals("regulatory", feature.type)
        assertEquals(2, feature.start)
        assertEquals(5, feature.end)
        assertEquals(Strand.REVERSE, feature.strand)
        assertEquals("Inserted annotation", feature.notes)
        assertEquals(mapOf("regulatory_class" to listOf("promoter")), feature.qualifiers)
        assertEquals("insert library feature", doc.undoLabel())

        doc.undo()
        assertEquals(original, doc.seq)
        assertFalse(doc.canUndo(), "bases and annotation must share one undo entry")
    }

    @Test
    fun primersAndFragmentsInsertBasesOnlyAndActionsRespectTheirContext() = onEdt {
        listOf(SavedKind.PRIMER, SavedKind.FRAGMENT).forEach { kind ->
            val prefs = Prefs()
            val doc = SeqDocument(Seq(name = "RNA target", bases = "AAAA", kind = SeqKind.RNA))
            val panel = LibraryPanel(prefs, doc, SequenceView(doc)) { _ -> }
            panel.addItem(
                SavedItem(
                    kind = kind,
                    name = kind.name.lowercase(),
                    bases = "TT",
                    sequenceKind = SeqKind.DNA,
                )
            )
            panel.libraryTable.setRowSelectionInterval(0, 0)

            val insert = panel.button("Insert at caret")
            val jump = panel.button("Jump to source")
            assertTrue(insert.isEnabled)
            assertFalse(jump.isEnabled)
            assertEquals("—", panel.libraryTable.model.getValueAt(0, 3))

            doc.moveCaret(1)
            insert.doClick()
            assertEquals("AUUAAA", doc.seq.bases, "DNA T must be normalized to U in the RNA target")
            assertTrue(doc.seq.features.isEmpty(), "$kind insertion must not create an annotation")
            doc.undo()
            assertEquals("AAAA", doc.seq.bases)

            val rebound = SeqDocument(Seq(name = "new RNA target", bases = "CCCC", kind = SeqKind.RNA))
            panel.bindDocument(rebound)
            rebound.moveCaret(2)
            insert.doClick()
            assertEquals("CCUUCC", rebound.seq.bases, "insertion must follow the document rebound into the panel")
            assertEquals("AAAA", doc.seq.bases, "the previously bound document must remain untouched")

            val protein = SeqDocument(Seq(bases = "MEEK", kind = SeqKind.PROTEIN))
            panel.bindDocument(protein)
            assertFalse(insert.isEnabled, "insertion must be disabled for a bound protein document")
        }
    }

    @Test
    fun wrappingCircularSourceCanStillBeRevealed() = onEdt {
        val prefs = Prefs()
        val doc = SeqDocument(Seq(name = "plasmid", bases = "AACCGGTT", topology = Topology.CIRCULAR))
        val panel = LibraryPanel(prefs, doc, SequenceView(doc)) { _ -> }
        panel.addItem(
            SavedItem(
                kind = SavedKind.FRAGMENT,
                name = "origin fragment",
                bases = "TTAACC",
                context = SavedContext(sourceName = "plasmid", start = 6, end = 12),
            )
        )

        panel.jumpToSource(0)

        assertEquals(0, doc.selectionStart)
        assertEquals(doc.seq.length, doc.selectionEnd)
    }

    private fun LibraryPanel.button(text: String): JButton =
        descendants(this, JButton::class.java).singleOrNull { it.text == text }
            ?: fail("No Library button named '$text'")

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
        var error: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
