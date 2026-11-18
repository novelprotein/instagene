package org.instagene.app.gui

import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.prefs.PrefsStore
import org.instagene.app.gui.tool.sequence.SequenceGraphics
import org.instagene.app.gui.tool.sequence.SequenceView
import org.instagene.core.sequence.Feature
import org.instagene.core.sequence.Seq
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.*

class SequenceGraphicsPreferencesTest {
    private fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    @Test fun savedChoicesUpdateEveryOpenEditorInBothDirections() {
        val directory = Files.createTempDirectory("sequence-graphics-prefs")
        val file = directory.resolve("prefs.json")
        try {
            edt {
                val prefs = Prefs(PrefsStore(file.toFile()))
                prefs.update { it.copy(detailedSequenceGraphics = false) }
                val first = InstaGeneContent(prefs = prefs)
                val second = InstaGeneContent(prefs = prefs)
                try {
                    val seq = Seq(bases = "ACGT".repeat(100), features = listOf(Feature("gene", "CDS", 0, 20)))
                    first.openSequence(seq)
                    second.openSequence(seq)
                    first.sequenceView.setSize(900, 500)
                    val simplifiedHeight = first.sequenceView.preferredSize.height
                    assertEquals(SequenceGraphics.SIMPLIFIED, first.sequenceView.graphicsMode)
                    assertEquals(SequenceGraphics.SIMPLIFIED, second.sequenceView.graphicsMode)

                    prefs.update { it.copy(detailedSequenceGraphics = true) }
                    assertEquals(SequenceGraphics.DETAILED, first.sequenceView.graphicsMode)
                    assertEquals(SequenceGraphics.DETAILED, second.sequenceView.graphicsMode)
                    assertTrue(first.sequenceView.preferredSize.height > simplifiedHeight, "The current editor must relayout immediately")

                    val reopened = InstaGeneContent(prefs = Prefs(PrefsStore(file.toFile())))
                    try { assertEquals(SequenceGraphics.DETAILED, reopened.sequenceView.graphicsMode) }
                    finally { reopened.dispose() }

                    prefs.update { it.copy(detailedSequenceGraphics = false) }
                    assertEquals(SequenceGraphics.SIMPLIFIED, first.sequenceView.graphicsMode)
                    assertEquals(SequenceGraphics.SIMPLIFIED, second.sequenceView.graphicsMode)
                    assertEquals(simplifiedHeight, first.sequenceView.preferredSize.height)
                    assertFalse(Prefs(PrefsStore(file.toFile())).value.detailedSequenceGraphics)

                    first.dispose()
                    prefs.update { it.copy(detailedSequenceGraphics = true) }
                    assertEquals(SequenceGraphics.SIMPLIFIED, first.sequenceView.graphicsMode, "Disposed editors must stop observing preferences")
                    assertEquals(SequenceGraphics.DETAILED, second.sequenceView.graphicsMode)
                } finally { first.dispose(); second.dispose() }
            }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
    }

    @Test fun unrelatedPreferencesDoNotRelayoutOrDisturbTheSequenceEditor() = edt {
        val prefs = Prefs()
        val content = InstaGeneContent(prefs = prefs)
        try {
            val doc = content.openSequence(Seq(bases = "ACGT".repeat(100)))
            content.sequenceView.setSize(900, 500)
            content.sequenceView.setFontSize(24)
            doc.select(8, 12)
            val geometry = SequenceView::class.java.getDeclaredField("geometry").apply { isAccessible = true }
            val before = geometry.get(content.sequenceView)
            prefs.update { it.copy(activeTab = (it.activeTab + 1) % content.toolTabs.tabCount) }
            assertSame(before, geometry.get(content.sequenceView), "Unrelated updates must not invalidate the row geometry")
            assertEquals(SequenceGraphics.DETAILED, content.sequenceView.graphicsMode)
            assertEquals(8 to 12, doc.selectionStart to doc.selectionEnd)
            assertEquals(12, doc.caret)
            assertEquals(24, content.sequenceView.fontSize())

            prefs.update { it.copy(detailedSequenceGraphics = false) }
            assertEquals(8 to 12, doc.selectionStart to doc.selectionEnd)
            assertEquals(12, doc.caret)
            assertEquals(24, content.sequenceView.fontSize())
            assertFalse(doc.isDirty)
        } finally { content.dispose() }
    }

    @Test fun backgroundUpdatesAreDispatchedToSwingAndQueuedUpdatesRespectDisposal() {
        for (disposeBeforeDispatch in listOf(false, true)) {
            val prefs = Prefs().apply { update { it.copy(detailedSequenceGraphics = false) } }
            lateinit var content: InstaGeneContent
            try {
                edt {
                    content = InstaGeneContent(prefs = prefs)
                    val failure = AtomicReference<Throwable?>()
                    val worker = Thread {
                        try { prefs.update { it.copy(detailedSequenceGraphics = true) } }
                        catch (error: Throwable) { failure.set(error) }
                    }
                    worker.start()
                    worker.join(5000)
                    assertFalse(worker.isAlive, "Preference notification must not wait for the Swing thread")
                    failure.get()?.let { throw it }
                    // The EDT is still inside this callback, so the queued UI update cannot have run.
                    assertEquals(SequenceGraphics.SIMPLIFIED, content.sequenceView.graphicsMode)
                    if (disposeBeforeDispatch) content.dispose()
                }
                edt {
                    assertEquals(if (disposeBeforeDispatch) SequenceGraphics.SIMPLIFIED else SequenceGraphics.DETAILED,
                        content.sequenceView.graphicsMode)
                }
            } finally { edt { content.dispose() } }
        }
    }

    @Test fun preferenceListenersCanBeRemovedWithoutAffectingOtherListeners() {
        val prefs = Prefs()
        var removedCalls = 0
        var retainedCalls = 0
        val removed: () -> Unit = { removedCalls++ }
        prefs.addListener(removed)
        prefs.addListener { retainedCalls++ }
        prefs.update { it.copy(detailedSequenceGraphics = false) }
        prefs.removeListener(removed)
        prefs.update { it.copy(detailedSequenceGraphics = true) }
        assertEquals(1, removedCalls)
        assertEquals(2, retainedCalls)
    }
}
