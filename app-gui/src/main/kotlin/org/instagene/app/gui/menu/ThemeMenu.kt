package org.instagene.app.gui.menu

import org.instagene.app.gui.prefs.Prefs
import org.instagene.app.gui.theme.ThemeManager
import java.awt.event.KeyEvent
import javax.swing.ButtonGroup
import javax.swing.JMenu
import javax.swing.JRadioButtonMenuItem

fun createThemeMenu(prefs: Prefs): JMenu {
    fun selectTheme(id: String) {
        if (ThemeManager.current() == id || !ThemeManager.apply(id)) return
        prefs.update { it.copy(theme = id) }
    }

    val group = ButtonGroup()
    return JMenu("Theme").apply {
        mnemonic = KeyEvent.VK_T
        ThemeManager.themes.forEach { theme ->
            val item = JRadioButtonMenuItem(theme.displayName)
            group.add(item)
            item.isSelected = theme.id == prefs.value.theme
            item.addActionListener {
                if (item.isSelected) selectTheme(theme.id)
            }
            add(item)
        }
    }
}
