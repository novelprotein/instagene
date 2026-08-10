package org.instagene.app.gui

import java.awt.BorderLayout
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
        border = javax.swing.BorderFactory.createEtchedBorder()

        docListener = SeqDocument.Listener { _, _ ->
            statusLabel.text = sequenceView.statusText()
        }
        doc.addListener(docListener!!)
        statusLabel.text = sequenceView.statusText()
    }

    /** Re-points this bar at another document (used when the active tab changes). */
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

    /** Re-picks the theme background when the look-and-feel changes. */
    override fun updateUI() {
        super.updateUI()
        background = Palette.BACKGROUND
    }
}
