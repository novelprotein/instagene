package org.instagene.app.gui.tool

import org.instagene.app.gui.analysis.AnalysisWorkspace
import org.instagene.app.gui.analysis.DetachedToolWindow
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.NcbiClient
import org.instagene.core.Seq
import java.awt.BorderLayout
import javax.swing.JPanel

/** Persistent GUI workspace for sequence analysis workflows. */
class AnalysisPanel(
    initial: SeqDocument,
    onOpenSequence: (Seq) -> Unit,
    onReveal: (Int, Int) -> Unit,
    ncbiClient: NcbiClient = NcbiClient(),
    ncbiPollIntervalMillis: Long = 2_000L,
    prefs: Prefs = Prefs(),
) : JPanel(BorderLayout()) {
    private var doc = initial
    private var listener: SeqDocument.Listener? = null

    internal val workspace: AnalysisWorkspace
    internal val detachedWindows = mutableListOf<DetachedToolWindow>()

    init {
        workspace = AnalysisWorkspace(
            onOpenSequence = onOpenSequence,
            onReveal = onReveal,
            ncbiClient = ncbiClient,
            ncbiPollIntervalMillis = ncbiPollIntervalMillis,
            prefs = prefs,
                onDetached = { panel, name, onClosed ->
                val window = DetachedToolWindow(panel, name) {
                    detachedWindows.remove(it)
                    onClosed()
                }
                detachedWindows += window
            },
        )
        add(workspace, BorderLayout.CENTER)
        bindDocument(initial)
    }

    fun bindDocument(newDoc: SeqDocument) {
        val changed = newDoc !== doc
        if (changed) {
            listener?.let { doc.removeListener(it) }
            doc = newDoc
        }
        if (listener == null) {
            listener = SeqDocument.Listener { _, reason ->
                if (reason == SeqDocument.Reason.SEQUENCE || reason == SeqDocument.Reason.SELECTION) refreshChildren()
            }
        }
        if (changed || !docListenerAttached) {
            doc.addListener(listener!!)
        }
        refreshChildren()
    }

    private var docListenerAttached = false

    private fun refreshChildren() {
        docListenerAttached = true
        workspace.bindDocument(doc)
    }

    fun selectTool(name: String) {
        workspace.selectTool(name)
    }

    /** Visible tool names, exposed for headless GUI smoke tests. */
    fun toolNames(): List<String> = workspace.toolNames()

    fun selectedTool(): String = workspace.selectedTool().orEmpty()
}
