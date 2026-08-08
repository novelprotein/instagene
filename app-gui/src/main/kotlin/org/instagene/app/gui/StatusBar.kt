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
class StatusBar(doc: SeqDocument, sequenceView: SequenceView) : JPanel(BorderLayout()) {

    private val statusLabel = JLabel("Ready")

    init {
        add(statusLabel, BorderLayout.CENTER)
        background = Palette.BACKGROUND
        border = javax.swing.BorderFactory.createEtchedBorder()

        doc.addListener { _, _ ->
            statusLabel.text = sequenceView.statusText()
        }
        statusLabel.text = sequenceView.statusText()
    }
}
