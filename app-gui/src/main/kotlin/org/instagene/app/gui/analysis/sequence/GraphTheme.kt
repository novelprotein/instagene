package org.instagene.app.gui.analysis.sequence
import org.instagene.app.gui.theme.Palette
import java.awt.Color

/** Appearance options for statistical graphs. */
internal enum class GraphTheme(val title: String) {
    APPLICATION("Match application"),
    LIGHT("Light"),
    DARK("Dark");

    override fun toString(): String = title

    fun background(): Color = when (this) {
        APPLICATION -> Palette.BACKGROUND
        LIGHT -> Color(0xFFFFFF)
        DARK -> Color(0x202328)
    }

    fun foreground(): Color = when (this) {
        APPLICATION -> Palette.TEXT
        LIGHT -> Color(0x202124)
        DARK -> Color(0xF1F3F4)
    }

    fun grid(): Color = when (this) {
        APPLICATION -> Palette.GRID
        LIGHT -> Color(0xDADDE1)
        DARK -> Color(0x50555C)
    }

    fun series(index: Int): Color = SERIES_COLORS[Math.floorMod(index, SERIES_COLORS.size)]

    companion object {
        fun fromId(id: String): GraphTheme = when (id.uppercase()) {
            "DARK" -> DARK
            "LIGHT" -> LIGHT
            else -> APPLICATION
        }

        private val SERIES_COLORS = listOf(
            Color(0x1565C0), Color(0x2E7D32), Color(0xE65100), Color(0x6A1B9A),
            Color(0x00695C), Color(0xBF360C), Color(0x880E4F), Color(0x006064),
        )
    }
}
