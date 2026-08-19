package org.instagene.app.gui.analysis

import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

internal class ToolHeader(
    toolName: String,
    private val onPopOut: () -> Unit,
) : JPanel(BorderLayout()) {
    private val popOutButton: JButton

    init {
        val nameLabel = JLabel(toolName).apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }
        val descLabel = JLabel(ToolDescriptions[toolName]).apply {
            foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
        }
        popOutButton = JButton("\u2922").apply {
            toolTipText = "Open in a separate window"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isFocusable = false
            addActionListener { onPopOut() }
        }

        val leftPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(nameLabel)
            add(descLabel)
        }

        add(leftPanel, BorderLayout.WEST)
        add(popOutButton, BorderLayout.EAST)
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
    }
}
