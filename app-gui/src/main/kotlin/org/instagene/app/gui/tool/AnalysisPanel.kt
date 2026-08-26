package org.instagene.app.gui.tool

import org.instagene.app.gui.analysis.AnalysisWorkspace
import org.instagene.app.gui.analysis.DetachedToolWindow
import org.instagene.app.gui.document.SeqDocument
import org.instagene.app.gui.prefs.Prefs
import org.instagene.core.ChromatogramRecord
import org.instagene.core.NcbiClient
import org.instagene.core.Seq
import java.awt.Window
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel

/** Persistent GUI workspace for sequence analysis workflows. */
class AnalysisPanel(
    initial: SeqDocument,
    private val onOpenSequence: (Seq) -> Unit,
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
        val currentListener = listener
        if ((changed || !docListenerAttached) && currentListener != null) {
            doc.addListener(currentListener)
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

    /** Shows a trace received through the application-wide file-open flow. */
    fun showChromatogram(record: ChromatogramRecord, sourceFile: File? = null) {
        workspace.showChromatogram(record, sourceFile)
    }

    /** Shows an imported, already aligned multi-record file. */
    fun showAlignment(sequences: List<Seq>, sourceFile: File? = null) {
        workspace.showAlignment(sequences, sourceFile)
    }

    /** Starts a Sanger-verification view from ABI/SCF reads already parsed by the shared file route. */
    fun showSangerVerification(records: List<ChromatogramRecord>, sourceFiles: List<File>) {
        workspace.showSangerVerification(records, sourceFiles)
    }

    /** Visible tool names, exposed for headless GUI smoke tests. */
    fun toolNames(): List<String> = workspace.toolNames()

    fun selectedTool(): String = workspace.selectedTool().orEmpty()

    /** Opens the shared identity-checked recipe-replay dialog from menus or the command palette. */
    fun showRecipeReplayDialog(owner: Window?) {
        org.instagene.app.gui.analysis.WorkflowRecipeReplayDialog(owner, onOpenSequence).isVisible = true
    }
}
