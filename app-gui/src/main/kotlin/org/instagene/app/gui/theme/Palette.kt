package org.instagene.app.gui.theme

import com.formdev.flatlaf.FlatLaf
import org.instagene.core.SeqKind
import java.awt.Color
import javax.swing.UIManager

/** Shared colours, so the editor, the map and the digest table agree. */
object Palette {

    /** Re-evaluated on each access so a runtime theme switch is picked up. */
    private val darkTheme: Boolean
        get() = runCatching { FlatLaf.isLafDark() }.getOrDefault(false)

    val BACKGROUND: Color
        get() = UIManager.getColor("Panel.background") ?: Color(0xFC, 0xFC, 0xFA)
    val GUTTER = Color(0x99, 0x9C, 0xA0)
    val GRID: Color get() = if (darkTheme) Color(0x52, 0x56, 0x5B) else Color(0xE4, 0xE4, 0xE0)
    val TEXT: Color get() = if (darkTheme) Color(0xE6, 0xE6, 0xE0) else Color(0x24, 0x26, 0x28)
    val MUTED: Color get() = if (darkTheme) Color(0x8C, 0x8F, 0x93) else Color(0x77, 0x7A, 0x7D)
    val SELECTION: Color
        get() = if (darkTheme) Color(0x47, 0x8B, 0xE8, 0x66) else Color(0x33, 0x77, 0xCC, 0x44)
    val CARET: Color get() = if (darkTheme) Color(0x8A, 0xB4, 0xF8) else Color(0x22, 0x44, 0x88)
    val CUT_MARK = Color(0xC0, 0x39, 0x2B)
    val START_CODON: Color get() = if (darkTheme) Color(0x3F, 0xA9, 0x6B, 0x88) else Color(0x3F, 0xA9, 0x6B, 0x66)
    val STOP_CODON: Color get() = if (darkTheme) Color(0xC0, 0x39, 0x2B, 0x88) else Color(0xC0, 0x39, 0x2B, 0x66)
    val EDITOR_ROW_ALT: Color get() = if (darkTheme) Color(0xFF, 0xFF, 0xFF, 0x06) else Color(0x24, 0x26, 0x28, 0x04)
    val EDITOR_ACTIVE_ROW: Color get() = if (darkTheme) Color(0x8A, 0xB4, 0xF8, 0x12) else Color(0x00, 0x66, 0xCC, 0x0C)
    val FEATURE_OUTLINE: Color get() = if (darkTheme) Color(0x13, 0x15, 0x18, 0xAA) else Color(0xFF, 0xFF, 0xFF, 0xDD)
    val MAP_BACKBONE: Color get() = if (darkTheme) Color(0x3A, 0x3F, 0x46) else Color(0xD8, 0xDE, 0xE6)
    val MAP_BACKBONE_HIGHLIGHT: Color get() = if (darkTheme) Color(0x63, 0x68, 0x70) else Color(0xF7, 0xF9, 0xFB)
    val MAP_GUIDE: Color get() = if (darkTheme) Color(0x9A, 0xA1, 0xAA, 0x88) else Color(0x69, 0x72, 0x7D, 0x88)
    val MAP_LABEL_BACKGROUND: Color get() = if (darkTheme) Color(0x20, 0x22, 0x26, 0xEE) else Color(0xFF, 0xFF, 0xFF, 0xF2)
    val MAP_LABEL_BORDER: Color get() = if (darkTheme) Color(0x66, 0x6C, 0x74, 0xAA) else Color(0xC6, 0xCF, 0xD9, 0xCC)

    /** The theme's accent color (FlatLaf "Component.accentColor"); a link-blue fallback when unset. */
    val ACCENT: Color
        get() = UIManager.getColor("Component.accentColor") ?: Color(0x00, 0x66, 0xCC)

    private val BASE_COLORS = mapOf(
        'A' to Color(0x2E, 0x8B, 0x57),
        'C' to Color(0x1E, 0x6F, 0xBA),
        'G' to Color(0xC8, 0x7A, 0x0E),
        'T' to Color(0xC0, 0x39, 0x2B),
        'U' to Color(0xB0, 0x3A, 0x8B),
        'N' to Color(0x8A, 0x8A, 0x8A),
        'R' to Color(0x27, 0x7A, 0x37), 'Y' to Color(0x1F, 0x58, 0x9D),
        'S' to Color(0x1E, 0x6F, 0xBA), 'W' to Color(0xA0, 0x60, 0x1C),
        'K' to Color(0xA0, 0x56, 0x1C), 'M' to Color(0x27, 0x7A, 0x57),
        'B' to Color(0x7A, 0x7A, 0x7A), 'D' to Color(0x9A, 0x7A, 0x3A),
        'H' to Color(0x9A, 0x5A, 0x5A), 'V' to Color(0x5A, 0x7A, 0x5A),
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

    /** The colour of nucleotide [c] (case-insensitive); non-base characters fall back to the muted grey. */
    fun baseColor(c: Char): Color = BASE_COLORS[c.uppercaseChar()] ?: MUTED

    /** Colour for an amino acid, falling back to the muted nucleotide grey. */
    fun aminoColor(c: Char): Color = AMINO_COLORS[c.uppercaseChar()] ?: MUTED

    /** Colour for a sequence character, chosen by [kind]. */
    fun charColor(c: Char, kind: SeqKind): Color =
        if (kind == SeqKind.PROTEIN) aminoColor(c) else baseColor(c)

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

    /** The fill for the [index]-th feature, cycling round the palette. */
    fun featureColor(index: Int): Color = FEATURE_COLORS[Math.floorMod(index, FEATURE_COLORS.size)]

    /** [color] with its alpha replaced by [alpha], RGB channels unchanged. */
    fun translucent(color: Color, alpha: Int): Color = Color(color.red, color.green, color.blue, alpha)
}
