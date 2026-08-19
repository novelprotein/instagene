package org.instagene.app.gui.analysis

import org.instagene.app.gui.theme.Palette
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

internal class ToolCard(
    val toolName: String,
    private val onSelect: () -> Unit,
) : JPanel(BorderLayout()) {
    private val nameLabel = JLabel(toolName)

    init {
        val pad = BorderFactory.createEmptyBorder(6, 10, 6, 10)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Palette.GRID),
            pad,
        )
        add(nameLabel, BorderLayout.CENTER)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isOpaque = true
        background = paletteBackground(false)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (!isSelected) background = paletteBackground(true)
            }
            override fun mouseExited(e: MouseEvent) {
                if (!isSelected) background = paletteBackground(false)
            }
            override fun mouseClicked(e: MouseEvent) {
                onSelect()
            }
        })
    }

    private var isSelected = false

    fun setSelected(selected: Boolean) {
        isSelected = selected
        background = if (selected) Palette.SELECTION else paletteBackground(false)
    }

    private fun paletteBackground(hover: Boolean): java.awt.Color {
        val base = Palette.BACKGROUND
        return if (hover) base.darker().brighter() else base
    }
}
