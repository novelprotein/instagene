package org.instagene.app.gui.analysis

import org.instagene.app.gui.document.SeqDocument
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame

internal class DetachedToolWindow(
    private val panel: BoundAnalysisPanel,
    toolName: String,
    private val onClosed: (DetachedToolWindow) -> Unit,
) : JFrame("InstaGene \u2014 $toolName") {

    init {
        contentPane = panel
        defaultCloseOperation = DISPOSE_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                onClosed(this@DetachedToolWindow)
            }
        })
        setSize(800, 600)
        setLocationRelativeTo(null)
        isVisible = true
    }

    fun bindDocument(doc: SeqDocument) {
        panel.bindDocument(doc)
    }
}
