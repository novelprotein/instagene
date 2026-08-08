package org.instagene.app.gui

import com.formdev.flatlaf.FlatLaf
import java.awt.Color
import javax.swing.UIManager

/** Shared colours, so the editor, the map and the digest table agree. */
object Palette {

    private val darkTheme = runCatching { FlatLaf.isLafDark() }.getOrDefault(false)

    val BACKGROUND: Color = UIManager.getColor("Panel.background") ?: Color(0xFC, 0xFC, 0xFA)
    val GUTTER = Color(0x99, 0x9C, 0xA0)
    val GRID: Color = if (darkTheme) Color(0x52, 0x56, 0x5B) else Color(0xE4, 0xE4, 0xE0)
    val TEXT: Color = if (darkTheme) Color(0xE6, 0xE6, 0xE0) else Color(0x24, 0x26, 0x28)
    val MUTED: Color = if (darkTheme) Color(0x8C, 0x8F, 0x93) else Color(0x77, 0x7A, 0x7D)
    val SELECTION = Color(0x33, 0x77, 0xCC, 0x44)
    val CARET: Color = if (darkTheme) Color(0x8A, 0xB4, 0xF8) else Color(0x22, 0x44, 0x88)
    val CUT_MARK = Color(0xC0, 0x39, 0x2B)

    private val BASE_COLORS = mapOf(
        'A' to Color(0x2E, 0x8B, 0x57),
        'C' to Color(0x1E, 0x6F, 0xBA),
        'G' to Color(0xC8, 0x7A, 0x0E),
        'T' to Color(0xC0, 0x39, 0x2B),
        'U' to Color(0xB0, 0x3A, 0x8B),
        'N' to Color(0x8A, 0x8A, 0x8A),
    )

    /**
     * Amino-acid colours grouped by side-chain chemistry, so a protein editor
     * gets the same kind of at-a-glance signal the nucleotide one does.
     */
    private val AMINO_COLORS = mapOf(
        'A' to Color(0x8A, 0x8F, 0x3C), 'V' to Color(0x8A, 0x8F, 0x3C), 'I' to Color(0x8A, 0x8F, 0x3C),
        'L' to Color(0x8A, 0x8F, 0x3C), 'M' to Color(0x8A, 0x8F, 0x3C),
        'F' to Color(0x6B, 0x7F, 0xC4), 'W' to Color(0x6B, 0x7F, 0xC4), 'Y' to Color(0x6B, 0x7F, 0xC4),
        'S' to Color(0x3F, 0xA9, 0x6B), 'T' to Color(0x3F, 0xA9, 0x6B), 'N' to Color(0x3F, 0xA9, 0x6B),
        'Q' to Color(0x3F, 0xA9, 0x6B), 'C' to Color(0x2A, 0xA1, 0xA8),
        'K' to Color(0xC0, 0x39, 0x2B), 'R' to Color(0xC0, 0x39, 0x2B), 'H' to Color(0xD5, 0x53, 0x53),
        'D' to Color(0xB0, 0x5C, 0xC4), 'E' to Color(0xB0, 0x5C, 0xC4),
        'G' to Color(0xCC, 0x5F, 0x91), 'P' to Color(0xE0, 0x8A, 0x2E),
        'B' to Color(0x77, 0x7A, 0x7D), 'Z' to Color(0x77, 0x7A, 0x7D),
        'J' to Color(0x77, 0x7A, 0x7D), 'X' to Color(0x8A, 0x8A, 0x8A),
        '*' to Color(0xC0, 0x39, 0x2B), '-' to Color(0x99, 0x9C, 0xA0),
    )

    fun baseColor(c: Char): Color = BASE_COLORS[c.uppercaseChar()] ?: MUTED

    /** Colour for an amino acid, falling back to the muted nucleotide grey. */
    fun aminoColor(c: Char): Color = AMINO_COLORS[c.uppercaseChar()] ?: MUTED

    /** Colour for a sequence character, chosen by [kind]. */
    fun charColor(c: Char, kind: org.instagene.core.SeqKind): Color =
        if (kind == org.instagene.core.SeqKind.PROTEIN) aminoColor(c) else baseColor(c)

    /** Distinct, readable fills for feature arcs and bars. */
    private val FEATURE_COLORS = listOf(
        Color(0x4C, 0x8B, 0xF5),
        Color(0x3F, 0xA9, 0x6B),
        Color(0xE0, 0x8A, 0x2E),
        Color(0xB0, 0x5C, 0xC4),
        Color(0xD5, 0x53, 0x53),
        Color(0x2A, 0xA1, 0xA8),
        Color(0x8A, 0x8F, 0x3C),
        Color(0xCC, 0x5F, 0x91),
    )

    fun featureColor(index: Int): Color = FEATURE_COLORS[Math.floorMod(index, FEATURE_COLORS.size)]

    fun translucent(color: Color, alpha: Int): Color = Color(color.red, color.green, color.blue, alpha)
}
