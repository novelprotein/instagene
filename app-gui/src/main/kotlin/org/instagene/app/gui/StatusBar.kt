package org.instagene.app.gui

import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Status bar showing current sequence statistics and editing state.
 */
class StatusBar(private val sequenceView: SequenceView) : JPanel(BorderLayout()) {

    private val statusLabel = JLabel("Ready")

    init {
        add(statusLabel, BorderLayout.CENTER)
        background = java.awt.Color(240, 240, 240)
        border = javax.swing.BorderFactory.createEtchedBorder()

        // Update status periodically
        val timer = javax.swing.Timer(100) {
            statusLabel.text = sequenceView.statusText()
        }
        timer.start()
    }
}
