package org.instagene.app.gui.ui

import org.instagene.app.gui.theme.Palette
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Status bar showing current sequence statistics and editing state.
 *
 * The text is recomputed on document changes rather than on a timer, so it is
 * always current and costs nothing while idle.
 */
class StatusBar(initial: SeqDocument, private val sequenceView: SequenceView) : JPanel(BorderLayout()) {

    private val statusLabel = JLabel("Ready")

    private var doc = initial
    private var docListener: SeqDocument.Listener? = null

    init {
        add(statusLabel, BorderLayout.CENTER)
        border = BorderFactory.createEtchedBorder()

        docListener = SeqDocument.Listener { _, _ ->
            statusLabel.text = sequenceView.statusText()
        }
        doc.addListener(docListener!!)
        statusLabel.text = sequenceView.statusText()
    }

    /** Binds this status bar to another document. */
    fun bindDocument(newDoc: SeqDocument) {
        if (newDoc !== doc) {
            docListener?.let { doc.removeListener(it) }
            doc = newDoc
            if (docListener != null) doc.addListener(docListener!!)
        }
        if (docListener == null) {
            docListener = SeqDocument.Listener { _, _ ->
                statusLabel.text = sequenceView.statusText()
            }
            doc.addListener(docListener!!)
        }
        statusLabel.text = sequenceView.statusText()
    }

    /** Refreshes the background after a look-and-feel change. */
    override fun updateUI() {
        super.updateUI()
        background = Palette.BACKGROUND
    }
}
