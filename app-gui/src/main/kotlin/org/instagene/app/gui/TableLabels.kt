package org.instagene.app.gui

import org.instagene.core.SeqKind
import java.util.Locale

/** Shared table terminology and measurement formatting for the desktop UI. */
object TableLabels {
    const val USE = "Use"
    const val ENZYME = "Enzyme"
    const val RECOGNITION_SITE = "Recognition site"
    const val OVERHANG = "Overhang"
    const val CUT_COUNT = "Cut count"
    const val DESCRIPTION = "Description"
    const val ORIGIN = "Origin"
    const val STRAND = "Strand"
    const val RECOGNITION_SEQUENCE = "Recognition sequence"
    const val CUT_TYPE = "Cut type"
    const val LENGTH = "Length"
    const val START = "Start"
    const val END = "End"
    const val NAME = "Name"
    const val TYPE = "Type"
    const val SEQUENCE = "Sequence"
    const val KIND = "Kind"
    const val SOURCE = "Source"
    const val NOT_APPLICABLE = "—"

    fun length(value: Int, kind: SeqKind): String = "$value ${unit(kind)}"

    fun unit(kind: SeqKind): String = when (kind) {
        SeqKind.DNA -> "bp"
        SeqKind.RNA -> "nt"
        SeqKind.PROTEIN -> "aa"
    }

    fun meltingTemperature(value: Double): String =
        String.format(Locale.ROOT, "%.1f °C", value)

    fun gcContent(value: Double): String =
        String.format(Locale.ROOT, "%.1f%%", value)
}
