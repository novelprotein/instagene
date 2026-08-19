package org.instagene.app.gui.analysis

import java.awt.Font

internal enum class ToolCategory(val icon: String, val fallback: String, val displayName: String) {
    SEARCH("\uD83D\uDD0D", "Search", "Search & Find"),
    SEQUENCE("\uD83E\uDDEC", "Seq", "Sequence Analysis"),
    CLONING("\u2702", "Clone", "Cloning & Design"),
    PCR("\uD83D\uDD2C", "PCR", "PCR & Sequencing"),
    UTILITIES("\u2699", "Util", "Utilities"),
}

internal fun iconLabel(cat: ToolCategory): String {
    val cp = cat.icon.codePointAt(0)
    return if (Font(Font.DIALOG, Font.PLAIN, 12).canDisplay(cp)) cat.icon else cat.fallback
}
