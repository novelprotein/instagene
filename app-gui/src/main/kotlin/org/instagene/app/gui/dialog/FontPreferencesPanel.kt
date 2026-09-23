package org.instagene.app.gui.dialog

import org.instagene.app.gui.prefs.UserPrefs
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager

/** Staged font choices: previewing never changes the application or saved preferences. */
class FontPreferencesPanel(current: UserPrefs) : JPanel(GridLayout(0, 2, 8, 8)) {
    private val families = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.sorted()
    private val family = JComboBox((listOf("Theme default") + families).toTypedArray()).apply {
        name = "interfaceFontFamily"
        selectedIndex = families.indexOfFirst { it.equals(current.interfaceFontFamily, ignoreCase = true) } + 1
    }
    private val size = JComboBox((listOf("Theme default") + (10..32).map { "$it pt" }).toTypedArray()).apply {
        name = "interfaceFontSize"
        selectedIndex = if (current.interfaceFontSize == 0) 0 else current.interfaceFontSize.coerceIn(10, 32) - 9
    }
    private val sample = JLabel("InstaGene · Aa Bb Cc 0123").apply { name = "interfaceFontPreview" }
    private val defaultFont = UIManager.getLookAndFeelDefaults().getFont("defaultFont")
        ?: UIManager.getFont("Label.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, 13)

    val selectedFamily: String? get() = families.getOrNull(family.selectedIndex - 1)
    val selectedSize: Int get() = if (size.selectedIndex == 0) 0 else size.selectedIndex + 9

    init {
        add(JLabel("Interface font")); add(family)
        add(JLabel("Font size")); add(size)
        add(JLabel("Preview")); add(sample)
        family.addActionListener { updatePreview() }
        size.addActionListener { updatePreview() }
        updatePreview()
    }

    private fun updatePreview() {
        sample.font = Font(selectedFamily ?: defaultFont.family, Font.PLAIN,
            selectedSize.takeIf { it != 0 } ?: defaultFont.size)
    }
}
